package com.example.arthasweb.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of Arthas diagnostic tools, mirroring the tools exposed by the official
 * Arthas MCP server. Each tool builds an Arthas command from its arguments and is
 * executed through the tunnel by {@link ArthasCommandExecutor}.
 *
 * <p>Used both by the chat (LLM tool-calling, target injected automatically) and by
 * the platform's own MCP server endpoint ({@code /mcp}), where callers pass {@code target}.
 */
public class ArthasMcpTools {

    public record Param(String name, String type, String description, boolean required, String def) {}

    public static class Tool {
        public final String name;
        public final String description;
        public final List<Param> params; // excludes the implicit "target" (agentId)
        public final java.util.function.Function<Map<String, Object>, String> build;

        public Tool(String name, String description, List<Param> params,
                    java.util.function.Function<Map<String, Object>, String> build) {
            this.name = name;
            this.description = description;
            this.params = params;
            this.build = build;
        }

        /** OpenAI function calling spec: {type, function: {name, description, parameters}}. */
        public Map<String, Object> toFunctionSpec() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", name);
            fn.put("description", description);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Param p : this.params) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("type", p.type);
                pm.put("description", p.description);
                if (p.def != null) pm.put("default", p.def);
                props.put(p.name, pm);
                if (p.required) required.add(p.name);
            }
            params.put("properties", props);
            if (!required.isEmpty()) params.put("required", required);
            fn.put("parameters", params);
            m.put("function", fn);
            return m;
        }

        /** MCP tool definition (includes target=agentId as required string). */
        public Map<String, Object> toMcpTool() {
            return spec(true);
        }

        private Map<String, Object> spec(boolean withTarget) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Param p : params) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("type", p.type);
                pm.put("description", p.description);
                if (p.def != null) pm.put("default", p.def);
                props.put(p.name, pm);
                if (p.required) required.add(p.name);
            }
            if (withTarget) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("type", "string");
                tm.put("description", "目标服务器的 agentId");
                props.put("target", tm);
                required.add("target");
            }
            schema.put("properties", props);
            schema.put("required", required);
            m.put("inputSchema", schema);
            return m;
        }
    }

    private static String str(Map<String, Object> a, String k, String d) {
        Object v = a.get(k);
        return v == null ? d : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v != null && (Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)));
    }

    private static String opt(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return (v == null || String.valueOf(v).isEmpty()) ? null : String.valueOf(v);
    }

    public static final List<Tool> TOOLS = List.of(
            new Tool("dashboard", "查看 JVM 整体实时面板：线程、内存、GC、Runtime、操作系统。", List.of(),
                    a -> "dashboard"),

            new Tool("thread", "查看线程栈/CPU 占用。可指定 topN（最忙 N 个线程）、threadId（具体线程）、state（线程状态如 BLOCKED/RUNNABLE）。",
                    List.of(
                            new Param("topN", "integer", "最忙的 N 个线程（默认 3）", false, "3"),
                            new Param("threadId", "integer", "指定线程 id", false, null),
                            new Param("state", "string", "按状态过滤，如 BLOCKED / RUNNABLE / WAITING", false, null),
                            new Param("blocked", "boolean", "true 时只显示被阻塞的线程", false, null)),
                    a -> {
                        String tid = opt(a, "threadId");
                        if (tid != null) return "thread " + tid;
                        StringBuilder sb = new StringBuilder("thread");
                        if (bool(a, "blocked")) sb.append(" -b");
                        String state = opt(a, "state");
                        if (state != null) sb.append(" --state ").append(state);
                        String topN = opt(a, "topN");
                        if (topN == null) topN = "3";
                        sb.append(" -n ").append(topN);
                        return sb.toString();
                    }),

            new Tool("jvm", "查看 JVM 基础信息：内存、GC、类加载、compiler、操作系统等。", List.of(),
                    a -> "jvm"),

            new Tool("sysprop", "查看/搜索 JVM 系统属性。可指定 key 精确查看。",
                    List.of(new Param("key", "string", "系统属性名（可选）", false, null)),
                    a -> "sysprop" + (opt(a, "key") != null ? " " + opt(a, "key") : "")),

            new Tool("sysenv", "查看/搜索 JVM 环境变量。可指定 key 精确查看。",
                    List.of(new Param("key", "string", "环境变量名（可选）", false, null)),
                    a -> "sysenv" + (opt(a, "key") != null ? " " + opt(a, "key") : "")),

            new Tool("sc", "搜索已加载的类。detail=true 显示类详情（classloader、来源等）。",
                    List.of(
                            new Param("classPattern", "string", "类名表达式，如 com.foo.*Service", true, null),
                            new Param("detail", "boolean", "true 显示详情（-d）", false, null)),
                    a -> "sc" + (bool(a, "detail") ? " -d " : " ") + str(a, "classPattern", "")),

            new Tool("sm", "查看已加载类的方法。detail=true 显示方法详情（包括行号）。",
                    List.of(
                            new Param("classPattern", "string", "类名表达式", true, null),
                            new Param("methodPattern", "string", "方法名（可选）", false, null),
                            new Param("detail", "boolean", "true 显示详情（-d）", false, null)),
                    a -> "sm " + str(a, "classPattern", "") + (opt(a, "methodPattern") != null ? " " + opt(a, "methodPattern") : "")
                            + (bool(a, "detail") ? " -d" : "")),

            new Tool("getstatic", "查看类的静态字段值。",
                    List.of(
                            new Param("className", "string", "完整类名", true, null),
                            new Param("fieldName", "string", "静态字段名", true, null)),
                    a -> "getstatic " + str(a, "className", "") + " " + str(a, "fieldName", "")),

            new Tool("ognl", "执行 OGNL 表达式，读取/计算运行时对象（只读优先）。",
                    List.of(new Param("expression", "string", "OGNL 表达式，如 '#springContext.beanFactory'", true, null)),
                    a -> "ognl " + str(a, "expression", "")),

            new Tool("watch", "方法执行观测：监控入参、返回值和异常。express 默认 {params,returnObj}。",
                    List.of(
                            new Param("className", "string", "类名表达式", true, null),
                            new Param("methodName", "string", "方法名", true, null),
                            new Param("express", "string", "观测表达式，默认 {params,returnObj}", false, "{params,returnObj}"),
                            new Param("condition", "string", "条件表达式（可选，如 'params[0]>0'）", false, null)),
                    a -> "watch " + str(a, "className", "") + " " + str(a, "methodName", "")
                            + " " + str(a, "express", "{params,returnObj}")
                            + (opt(a, "condition") != null ? " -x 3 \"" + opt(a, "condition") + "\"" : "")),

            new Tool("trace", "方法内部调用链路追踪，并输出每个节点的耗时。适合定位慢方法。",
                    List.of(
                            new Param("className", "string", "类名表达式", true, null),
                            new Param("methodName", "string", "方法名", true, null),
                            new Param("condition", "string", "条件表达式（可选）", false, null)),
                    a -> "trace " + str(a, "className", "") + " " + str(a, "methodName", "")
                            + (opt(a, "condition") != null ? " \"" + opt(a, "condition") + "\"" : "")),

            new Tool("stack", "查看方法被调用的调用栈（谁调用了它）。",
                    List.of(
                            new Param("className", "string", "类名表达式", true, null),
                            new Param("methodName", "string", "方法名", true, null)),
                    a -> "stack " + str(a, "className", "") + " " + str(a, "methodName", "")),

            new Tool("monitor", "方法执行监控：调用次数、成功失败、RT、失败率（按周期统计）。",
                    List.of(
                            new Param("className", "string", "类名表达式", true, null),
                            new Param("methodName", "string", "方法名", true, null),
                            new Param("cycle", "integer", "统计周期秒数（默认 5）", false, "5")),
                    a -> "monitor -c " + str(a, "cycle", "5") + " " + str(a, "className", "") + " " + str(a, "methodName", "")),

            new Tool("classloader", "查看类加载器层级与统计。hash 指定时显示该加载器加载的类。",
                    List.of(new Param("hash", "string", "类加载器 hash（可选）", false, null)),
                    a -> "classloader" + (opt(a, "hash") != null ? " -l " + opt(a, "hash") : "")),

            new Tool("heapdump", "生成堆转储文件（hprof）。path 可选，默认临时目录。",
                    List.of(new Param("path", "string", "输出路径（可选）", false, null)),
                    a -> "heapdump" + (opt(a, "path") != null ? " " + opt(a, "path") : "")),

            new Tool("execute_arthas_command", "执行任意原生 Arthas 命令（兜底能力）。",
                    List.of(new Param("command", "string", "完整 Arthas 命令，如 'trace com.foo.Bar baz'", true, null)),
                    a -> str(a, "command", ""))
    );

    public static Tool find(String name) {
        return TOOLS.stream().filter(t -> t.name.equals(name)).findFirst().orElse(null);
    }

    public static List<Map<String, Object>> mcpToolList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : TOOLS) list.add(t.toMcpTool());
        return list;
    }

    public static List<Map<String, Object>> functionSpecs() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : TOOLS) list.add(t.toFunctionSpec());
        return list;
    }
}
