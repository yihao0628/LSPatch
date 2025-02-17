package org.lsposed.patch.base;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Created by Wind
 */
public abstract class BaseCommand {

    private String onlineHelp;

    protected Map<String, Option> optMap = new HashMap<String, Option>();

    @Opt(opt = "h", longOpt = "help", hasArg = false, description = "Print this help message")
    private boolean printHelp = false;

    protected String[] remainingArgs;
    protected String[] orginalArgs;

    @Retention(value = RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.FIELD })
    static public @interface Opt {
        String argName() default "";

        String description() default "";

        boolean hasArg() default true;

        String longOpt() default "";

        String opt() default "";

        boolean required() default false;
    }

    static protected class Option implements Comparable<Option> {
        public String argName = "arg";
        public String description;
        public Field field;
        public boolean hasArg = true;
        public String longOpt;
        public String opt;
        public boolean required = false;

        @Override
        public int compareTo(Option o) {
            int result = s(this.opt, o.opt);
            if (result == 0) {
                result = s(this.longOpt, o.longOpt);
                if (result == 0) {
                    result = s(this.argName, o.argName);
                    if (result == 0) {
                        result = s(this.description, o.description);
                    }
                }
            }
            return result;
        }

        private static int s(String a, String b) {
            if (a != null && b != null) {
                return a.compareTo(b);
            } else if (a != null) {
                return 1;
            } else if (b != null) {
                return -1;
            } else {
                return 0;
            }
        }

        public String getOptAndLongOpt() {
            StringBuilder sb = new StringBuilder();
            boolean havePrev = false;
            if (opt != null && opt.length() > 0) {
                sb.append("-").append(opt);
                havePrev = true;
            }
            if (longOpt != null && longOpt.length() > 0) {
                if (havePrev) {
                    sb.append(", ");
                }
                sb.append("--").append(longOpt);
            }
            return sb.toString();
        }

    }

    @Retention(value = RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.TYPE })
    static public @interface Syntax {

        String cmd();

        String desc() default "";

        String onlineHelp() default "";

        String syntax() default "";
    }

    public void doMain(String... args) {
        try {
            initOptions();
            parseSetArgs(args);
            doCommandLine();
        } catch (HelpException e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 0) {
                System.err.println("ERROR: " + msg);
            }
            usage();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    protected abstract void doCommandLine() throws Exception;

    protected String getVersionString() {
        return getClass().getPackage().getImplementationVersion();
    }

    protected void initOptions() {
        initOptionFromClass(this.getClass());
    }

    protected void initOptionFromClass(Class<?> clz) {
        if (clz == null) {
            return;
        } else {
            initOptionFromClass(clz.getSuperclass());
        }

        Syntax syntax = clz.getAnnotation(Syntax.class);
        if (syntax != null) {
            this.onlineHelp = syntax.onlineHelp();
        }

        Field[] fs = clz.getDeclaredFields();
        for (Field f : fs) {
            Opt opt = f.getAnnotation(Opt.class);
            if (opt != null) {
                f.setAccessible(true);
                Option option = new Option();
                option.field = f;
                option.description = opt.description();
                option.hasArg = opt.hasArg();
                option.required = opt.required();
                if ("".equals(opt.longOpt()) && "".equals(opt.opt())) {   // into automode
                    option.longOpt = fromCamel(f.getName());
                    if (f.getType().equals(boolean.class)) {
                        option.hasArg=false;
                        try {
                            if (f.getBoolean(this)) {
                                throw new RuntimeException("the value of " + f +
                                        " must be false, as it is declared as no args");
                            }
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    checkConflict(option, "--" + option.longOpt);
                    continue;
                }
                if (!opt.hasArg()) {
                    if (!f.getType().equals(boolean.class)) {
                        throw new RuntimeException("the type of " + f
                                + " must be boolean, as it is declared as no args");
                    }

                    try {
                        if (f.getBoolean(this)) {
                            throw new RuntimeException("the value of " + f +
                                    " must be false, as it is declared as no args");
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
                boolean haveLongOpt = false;
                if (!"".equals(opt.longOpt())) {
                    option.longOpt = opt.longOpt();
                    checkConflict(option, "--" + option.longOpt);
                    haveLongOpt = true;
                }
                if (!"".equals(opt.argName())) {
                    option.argName = opt.argName();
                }
                if (!"".equals(opt.opt())) {
                    option.opt = opt.opt();
                    checkConflict(option, "-" + option.opt);
                } else {
                    if (!haveLongOpt) {
                        throw new RuntimeException("opt or longOpt is not set in @Opt(...) " + f);
                    }
                }
            }
        }
    }

    private void checkConflict(Option option, String key) {
        if (optMap.containsKey(key)) {
            Option preOption = optMap.get(key);
            throw new RuntimeException(String.format("[@Opt(...) %s] conflict with [@Opt(...) %s]",
                    preOption.field.toString(), option.field
            ));
        }
        optMap.put(key, option);
    }

    private static String fromCamel(String name) {
        if (name.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        char[] charArray = name.toCharArray();
        sb.append(Character.toLowerCase(charArray[0]));
        for (int i = 1; i < charArray.length; i++) {
            char c = charArray[i];
            if (Character.isUpperCase(c)) {
                sb.append("-").append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    protected void parseSetArgs(String... args) throws IllegalArgumentException, IllegalAccessException {
        this.orginalArgs = args;
        List<String> remainsOptions = new ArrayList<>();
        Set<Option> requiredOpts = collectRequriedOptions(optMap);
        Option needArgOpt = null;
        for (String s : args) {
            if (needArgOpt != null) {
                Field field = needArgOpt.field;
                Class clazz = field.getType();
                if (clazz.equals(List.class)) {
                    try {
                        List<Object> object = ((List<Object>) field.get(this));

                        // 获取List对象的泛型类型
                        ParameterizedType listGenericType = (ParameterizedType) field.getGenericType();
                        Type[] listActualTypeArguments = listGenericType.getActualTypeArguments();
                        Class typeClazz = (Class) listActualTypeArguments[0];
                        object.add(convert(s, typeClazz));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    field.set(this, convert(s, clazz));
                }
                needArgOpt = null;
            } else if (s.startsWith("-")) {// its a short or long option
                Option opt = optMap.get(s);
                requiredOpts.remove(opt);
                if (opt == null) {
                    System.err.println("ERROR: Unrecognized option: " + s);
                    throw new HelpException();
                } else {
                    if (opt.hasArg) {
                        needArgOpt = opt;
                    } else {
                        opt.field.set(this, true);
                    }
                }
            } else {
                remainsOptions.add(s);
            }
        }

        if (needArgOpt != null) {
            System.err.println("ERROR: Option " + needArgOpt.getOptAndLongOpt() + " need an argument value");
            throw new HelpException();
        }
        this.remainingArgs = remainsOptions.toArray(new String[remainsOptions.size()]);
        if (this.printHelp) {
            throw new HelpException();
        }
        if (!requiredOpts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("ERROR: Options: ");
            boolean first = true;
            for (Option option : requiredOpts) {
                if (first) {
                    first = false;
                } else {
                    sb.append(" and ");
                }
                sb.append(option.getOptAndLongOpt());
            }
            sb.append(" is required");
            System.err.println(sb.toString());
            throw new HelpException();
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected Object convert(String value, Class type) {
        if (type.equals(String.class)) {
            return value;
        }
        if (type.equals(int.class) || type.equals(Integer.class)) {
            return Integer.parseInt(value);
        }
        if (type.equals(long.class) || type.equals(Long.class)) {
            return Long.parseLong(value);
        }
        if (type.equals(float.class) || type.equals(Float.class)) {
            return Float.parseFloat(value);
        }
        if (type.equals(double.class) || type.equals(Double.class)) {
            return Double.parseDouble(value);
        }
        if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return Boolean.parseBoolean(value);
        }
        if (type.equals(File.class)) {
            return new File(value);
        }
        if (type.equals(Path.class)) {
            return new File(value).toPath();
        }
        try {
            type.asSubclass(Enum.class);
            return Enum.valueOf(type, value);
        } catch (Exception e) {
        }

        throw new RuntimeException("can't convert [" + value + "] to type " + type);
    }

    private Set<Option> collectRequriedOptions(Map<String, Option> optMap) {
        Set<Option> options = new HashSet<Option>();
        for (Map.Entry<String, Option> e : optMap.entrySet()) {
            Option option = e.getValue();
            if (option.required) {
                options.add(option);
            }
        }
        return options;
    }

    @SuppressWarnings("serial")
    protected static class HelpException extends RuntimeException {

        public HelpException() {
            super();
        }

        public HelpException(String message) {
            super(message);
        }

    }

    protected void usage() {
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        out.println();

        if (this.optMap.size() > 0) {
            out.println("Options:");
            out.println();
        }

        TreeSet<Option> options = new TreeSet<>(this.optMap.values());
        StringBuilder sb = new StringBuilder();
        for (Option option : options) {
            sb.setLength(0);
            sb.append("  ").append(option.getOptAndLongOpt());
            if (option.hasArg) {
                sb.append(" <").append(option.argName).append(">");
            }
            String desc = option.description;
            if (desc == null || desc.length() == 0) {// no description
                out.println(sb);
            } else {
                int maxWidth = 15;
                if(sb.length() >= maxWidth) {
                    sb.append('\n');
                    sb.append(" ".repeat(maxWidth));
                }
                else {
                    sb.append(" ".repeat(maxWidth - sb.length()));
                }
                sb.append(desc);
                out.println(sb);
            }
            out.println();
        }
        String ver = getVersionString();
        if (ver != null && !"".equals(ver)) {
            out.println("version: " + ver);
        }
        out.flush();
    }

    public static String getBaseName(String fn) {
        int x = fn.lastIndexOf('.');
        return x >= 0 ? fn.substring(0, x) : fn;
    }

    // 获取文件不包含后缀的名称
    public static String getBaseName(File fn) {
        return getBaseName(fn.getName());
    }

}
