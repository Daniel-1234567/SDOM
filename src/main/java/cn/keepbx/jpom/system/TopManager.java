package cn.keepbx.jpom.system;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.StrSpliter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.cron.CronUtil;
import cn.hutool.cron.Scheduler;
import cn.jiangzeyin.common.DefaultSystemLog;
import cn.jiangzeyin.pool.ThreadPoolService;
import cn.keepbx.jpom.common.commander.AbstractCommander;
import cn.keepbx.jpom.socket.SocketSessionUtil;
import cn.keepbx.jpom.util.CommandUtil;
import cn.keepbx.jpom.util.JvmUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sun.management.OperatingSystemMXBean;
import com.sun.tools.attach.VirtualMachine;

import javax.websocket.Session;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * top命令管理，保证整个服务器只获取一个top命令
 *
 * @author jiangzeyin
 * @date 2018/10/2
 */
public class TopManager {

    private static final Set<Session> SESSIONS = new HashSet<>();
    private static final String CRON_ID = "topMonitor";
    private static ExecutorService executorService = ThreadPoolService.newCachedThreadPool(TopManager.class);
    /**
     * 是否开启首页监听（自动刷新）
     */
    private static final AtomicBoolean WATCH = new AtomicBoolean(false);
    /**
     * 锁定查看进程信息
     */
    private static final AtomicBoolean ATOMIC_BOOLEAN = new AtomicBoolean(false);

    /**
     * 添加top 命令监听
     *
     * @param session 回话
     */
    public static void addMonitor(Session session) {
        SESSIONS.add(session);
        addCron();
    }

    /**
     * 移除top 命令监控
     *
     * @param session 回话
     */
    public static void removeMonitor(Session session) {
        SESSIONS.remove(session);
        close();
    }

    /**
     * 创建定时执行top
     */
    private static void addCron() {
        if (WATCH.get()) {
            return;
        }
        CronUtil.remove(CRON_ID);
        CronUtil.schedule(CRON_ID, "0/5 * * * * ?", () -> {
            //发送监控信息
            try {
                String topInfo;
                if (AbstractCommander.OS_INFO.isLinux()) {
                    String result = CommandUtil.execCommand("top -b -n 1");
                    topInfo = getTopMonitor(result);
                } else {
                    topInfo = getWindowsMonitor();
                }
                send(topInfo);
            } catch (Exception e) {
                DefaultSystemLog.ERROR().error(e.getMessage(), e);
            }
            //发送首页进程列表信息
            sendProcessList();
        });
        Scheduler scheduler = CronUtil.getScheduler();
        if (!scheduler.isStarted()) {
            CronUtil.start();
        }
        WATCH.set(true);
    }


    /**
     * 发送首页进程列表信息
     */
    private static void sendProcessList() {
        executorService.execute(() -> {
            JSONArray array = getProcessList();
            if (array != null) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("processList", array);
                send(jsonObject.toJSONString());
            }
        });
    }

    public static JSONArray getProcessList() {
        if (ATOMIC_BOOLEAN.get()) {
            return null;
        }
        JSONArray array;
        try {
            ATOMIC_BOOLEAN.set(true);
            if (AbstractCommander.OS_INFO.isLinux()) {
                String head = CommandUtil.execSystemCommand("top -b -n 1 | head -7");
                String s = CommandUtil.execSystemCommand("top -b -n 1 | grep java");
                array = formatLinuxTop(head + s);
            } else {
                String s = CommandUtil.execSystemCommand("tasklist /V | findstr java");
                array = formatWindowsProcess(s, false);
            }
            if (array != null) {
                // 获取进程主端口信息
                array.forEach(o -> {
                    JSONObject jsonObject = (JSONObject) o;
                    int pid = jsonObject.getIntValue("pid");
                    if (pid <= 0) {
                        return;
                    }
                    String port = AbstractCommander.getInstance().getMainPort(pid);
                    jsonObject.put("port", port);
                    String jpomName = StrUtil.DASHED;
                    try {
                        jpomName = AbstractCommander.getInstance().getJpomNameByPid(pid);
                    } catch (IOException e) {
                        DefaultSystemLog.ERROR().error("解析进程失败", e);
                    }
                    jsonObject.put("jpomName", jpomName);
                });
            }
            return array;
        } catch (Exception e) {
            DefaultSystemLog.ERROR().error(e.getMessage(), e);
        } finally {
            ATOMIC_BOOLEAN.set(false);
        }
        return null;
    }

    /**
     * 获取windows 监控
     * https://docs.oracle.com/javase/7/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html
     *
     * @return 返回cpu占比和内存占比
     */
    public static String getWindowsMonitor() {
        OperatingSystemMXBean operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("top", true);
        JSONArray memory = new JSONArray();
        {
            long totalPhysicalMemorySize = operatingSystemMXBean.getTotalPhysicalMemorySize();
            long freePhysicalMemorySize = operatingSystemMXBean.getFreePhysicalMemorySize();
            //单位 kb
            memory.add(putObject("占用内存", (totalPhysicalMemorySize - freePhysicalMemorySize) / 1024f, "memory"));
            memory.add(putObject("空闲内存", freePhysicalMemorySize / 1024f, "memory"));
        }
        JSONArray cpus = new JSONArray();
        {
            //最近系统cpu使用量
            double systemCpuLoad = operatingSystemMXBean.getSystemCpuLoad();
            if (systemCpuLoad <= 0) {
                systemCpuLoad = 0;
            }
            cpus.add(putObject("占用cpu", systemCpuLoad, "cpu"));
            cpus.add(putObject("空闲cpu", 1 - systemCpuLoad, "cpu"));
        }
        jsonObject.put("memory", memory);
        jsonObject.put("cpu", cpus);
        jsonObject.put("disk", getHardDisk());
        return jsonObject.toJSONString();
    }

    /**
     * 磁盘占用
     *
     * @return 磁盘占用
     */
    private static JSONArray getHardDisk() {
        File[] files = File.listRoots();
        long freeSpace = 0;
        long useAbleSpace = 0;
        for (File file : files) {
            long free = file.getFreeSpace();
            freeSpace += free;
            useAbleSpace += file.getTotalSpace() - free;
        }
        JSONArray array = new JSONArray();
        //单位 kb
        array.add(putObject("已使用磁盘", useAbleSpace / 1024f, "disk"));
        array.add(putObject("空闲磁盘", freeSpace / 1024f, "disk"));
        return array;
    }

    /**
     * linux 监控
     *
     * @param content top信息
     * @return 返回cpu占比和内存占比
     */
    public static String getTopMonitor(String content) {
        if (StrUtil.isEmpty(content)) {
            return "top查询失败";
        }
        String[] split = content.split("\n");
        int length = split.length;
        JSONObject jsonObject = new JSONObject();
        if (length >= 2) {
            String cpus = split[2];
            //cpu占比
            JSONArray cpu = getLinuxCpu(cpus);
            jsonObject.put("cpu", cpu);
        }
        if (length >= 3) {
            String mem = split[3];
            //内存占比
            JSONArray memory = getLinuxMemory(mem);
            jsonObject.put("memory", memory);
        }
        jsonObject.put("top", true);
        jsonObject.put("disk", getHardDisk());
        return jsonObject.toJSONString();
    }

    /**
     * 将windows的tasklist转为集合
     *
     * @param header 是否包含投信息
     * @param result 进程信息
     * @return jsonArray
     */
    public static JSONArray formatWindowsProcess(String result, boolean header) {
        List<String> list = StrSpliter.splitTrim(result, "\n", true);
        JSONArray array = new JSONArray();
        for (int i = header ? 2 : 0, len = list.size(); i < len; i++) {
            String param = list.get(i);
            List<String> memList = StrSpliter.splitTrim(param, " ", true);
            String name = memList.get(0);
            JSONObject item = new JSONObject();
            int pid = Convert.toInt(memList.get(1), 0);
            item.put("pid", pid);
            item.put("COMMAND", name);
            //使用内存 kb
            String mem = memList.get(4).replace(",", "");
            long aLong = Convert.toLong(mem, 0L);
            item.put("RES", aLong / 1024 + " MB");
            String status = memList.get(6);
            if ("RUNNING".equalsIgnoreCase(status)) {
                item.put("S", "运行");
            } else if ("SUSPENDED".equalsIgnoreCase(status)) {
                item.put("S", "睡眠");
            } else if ("NOT RESPONDING".equalsIgnoreCase(status)) {
                item.put("S", "无响应");
            } else {
                item.put("S", "未知");
            }
            item.put("USER", memList.get(7));
            item.put("TIME", memList.get(8));
            item.put("PR", "-");
            item.put("NI", "-");
            item.put("VIRT", "-");
            item.put("SHR", "-");
            try {
                VirtualMachine virtualMachine = VirtualMachine.attach(String.valueOf(pid));
                OperatingSystemMXBean operatingSystemMXBean = JvmUtil.getOperatingSystemMXBean(virtualMachine);
                if (operatingSystemMXBean != null) {
                    //最近jvm cpu使用率
                    double processCpuLoad = operatingSystemMXBean.getProcessCpuLoad() * 100;
                    if (processCpuLoad <= 0) {
                        processCpuLoad = 0;
                    }
                    item.put("CPU", String.format("%.2f", processCpuLoad) + "%");
                    //服务器总内存
                    long totalMemorySize = operatingSystemMXBean.getTotalPhysicalMemorySize();
                    BigDecimal total = new BigDecimal(totalMemorySize / 1024);
                    // 进程
                    double v = new BigDecimal(aLong).divide(total, 4, BigDecimal.ROUND_HALF_UP).doubleValue() * 100;
                    item.put("MEM", String.format("%.2f", v) + "%");
                }
            } catch (Exception ignored) {
                item.put("MEM", "-");
                item.put("CPU", "-");
            }
            array.add(item);
        }
        return array;
    }


    /**
     * 将linux的top信息转为集合
     *
     * @param top top
     */
    public static JSONArray formatLinuxTop(String top) {
        List<String> list = StrSpliter.splitTrim(top, "\n", true);
        if (list.size() < 5) {
            return null;
        }
        //top信息名称栏
        String topName = list.get(5);
        List<String> nameList = StrSpliter.splitTrim(topName, " ", true);
        JSONArray array = new JSONArray();
        for (int j = 6; j < list.size(); j++) {
            String ram = list.get(j);
            List<String> ramList = StrSpliter.splitTrim(ram, " ", true);
            if (ramList.size() < nameList.size()) {
                continue;
            }
            JSONObject item = new JSONObject();
            for (int i = 0; i < nameList.size(); i++) {
                String name = nameList.get(i);
                String value = ramList.get(i);
                if (i == 0) {
                    item.put("pid", value);
                    continue;
                }
                name = name.replaceAll("%", "").replace("+", "");
                if ("VIRT".equalsIgnoreCase(name) || "RES".equalsIgnoreCase(name) || "SHR".equalsIgnoreCase(name)) {
                    if (value.endsWith("g")) {
                        value = String.format("%.2f MB", Convert.toDouble(value.replace("g", "")) * 1024);
                    } else {
                        value = Convert.toLong(value) / 1024 + " MB";
                    }
                }
                if ("�".equals(name)) {
                    name = "S";
                }
                if ("S".equalsIgnoreCase(name)) {
                    if ("S".equalsIgnoreCase(value)) {
                        value = "睡眠";
                    } else if ("R".equalsIgnoreCase(value)) {
                        value = "运行";
                    } else if ("T".equalsIgnoreCase(value)) {
                        value = "跟踪/停止";
                    } else if ("Z".equalsIgnoreCase(value)) {
                        value = "僵尸进程 ";
                    } else if ("D".equalsIgnoreCase(value)) {
                        value = "不可中断的睡眠状态 ";
                    } else if ("i".equalsIgnoreCase(value)) {
                        value = "多线程 ";
                    }
                }
                if ("CPU".equalsIgnoreCase(name) || "MEM".equalsIgnoreCase(name)) {
                    value += "%";
                }
                item.put(name, value);
            }
            array.add(item);
        }
        return array;
    }

    /**
     * 获取内存信息
     *
     * @param info 内存信息
     * @return 内存信息
     */
    private static JSONArray getLinuxMemory(String info) {
        if (StrUtil.isEmpty(info)) {
            return null;
        }
        int index = info.indexOf(":") + 1;
        String[] split = info.substring(index).split(",");
        JSONArray memory = new JSONArray();
        for (String str : split) {
            str = str.trim();
//            509248k total — 物理内存总量（509M）
//            495964k used — 使用中的内存总量（495M）
//            13284k free — 空闲内存总量（13M）
//            25364k buffers — 缓存的内存量 （25M）
            if (str.endsWith("free")) {
                memory.add(putObject("空闲内存", str.replace("free", "").trim(), "memory"));
            }
            if (str.endsWith("used")) {
                memory.add(putObject("使用中的内存", str.replace("used", "").trim(), "memory"));
            }
            if (str.endsWith("buff/cache")) {
                memory.add(putObject("缓存的内存", str.replace("buff/cache", "").trim(), "memory"));
            }
        }
        return memory;
    }

    /**
     * 获取cpu信息
     *
     * @param info cpu信息
     * @return cpu信息
     */
    private static JSONArray getLinuxCpu(String info) {
        if (StrUtil.isEmpty(info)) {
            return null;
        }
        int i = info.indexOf(":");
        String[] split = info.substring(i + 1).split(",");
        JSONArray cpu = new JSONArray();
        for (String str : split) {
            str = str.trim();
//            1.3% us — 用户空间占用CPU的百分比。
//            1.0% sy — 内核空间占用CPU的百分比。
//            0.0% ni — 改变过优先级的进程占用CPU的百分比
//            97.3% id — 空闲CPU百分比
//            0.0% wa — IO等待占用CPU的百分比
//            0.3% hi — 硬中断（Hardware IRQ）占用CPU的百分比
//            0.0% si — 软中断（Software Interrupts）占用CPU的百分比
            String value = str.substring(0, str.length() - 2).trim();
            String tag = str.substring(str.length() - 2);
            switch (tag) {
                case "us":
                    cpu.add(putObject("用户空间", value, "cpu"));
                    break;
                case "sy":
                    cpu.add(putObject("内核空间", value, "cpu"));
                    break;
                case "ni":
                    cpu.add(putObject("改变过优先级的进程", value, "cpu"));
                    break;
                case "id":
                    cpu.add(putObject("空闲CPU", value, "cpu"));
                    break;
                case "wa":
                    cpu.add(putObject("IO等待", value, "cpu"));
                    break;
                case "hi":
                    cpu.add(putObject("硬中断", value, "cpu"));
                    break;
                case "si":
                    cpu.add(putObject("软中断", value, "cpu"));
                    break;
                default:
                    break;
            }
        }
        return cpu;
    }

    private static JSONObject putObject(String name, Object value, String type) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("name", name);
        jsonObject.put("value", value);
        jsonObject.put("type", type);
        return jsonObject;
    }

    /**
     * 同步发送消息
     *
     * @param content 内容
     */
    private static void send(String content) {
        synchronized (TopManager.class) {
            Iterator<Session> iterator = SESSIONS.iterator();
            while (iterator.hasNext()) {
                Session session = iterator.next();
                content = content.replaceAll("\n", "<br/>");
                content = content.replaceAll(" ", "&nbsp;&nbsp;");
                try {
                    SocketSessionUtil.send(session, content);
                } catch (IOException e) {
                    DefaultSystemLog.ERROR().error("消息失败", e);
                    try {
                        session.close();
                        iterator.remove();
                    } catch (IOException ignored) {
                    }
                }
            }
            close();
        }
    }

    /**
     * 关闭top监听
     */
    private static void close() {
        // 如果没有队列就停止监听
        int size = SESSIONS.size();
        if (size > 0) {
            return;
        }
        //
        CronUtil.remove(CRON_ID);
        WATCH.set(false);
    }
}
