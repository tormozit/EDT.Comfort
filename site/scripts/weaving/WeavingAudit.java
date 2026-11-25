import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.wiring.BundleWiring;

public final class WeavingAudit {
    private record Installed(String id, Version version, Path path) {}
    private record Target(String stage, String bundle, List<String> classes, String hook) {}
    private record Resolved(Installed bundle, String name) {}

    private int passed;
    private int failed;

    public static void main(String[] args) throws Exception {
        var audit = new WeavingAudit();
        var registry = readTargets(Path.of(args[0]));
        var installed = readProfile(Path.of(args[1]));
        var dependencies = readProfile(Path.of(args[2]));
        var earlyArtifact = Path.of(args[3]);
        var mainArtifact = Path.of(args[4]);
        var logRoot = Path.of(args[5]);
        Files.createDirectories(logRoot);
        System.getProperties().put("tormozit.weaving.auditLogRoot", logRoot);
        try (var early = loader(earlyArtifact, dependencies);
             var main = loader(mainArtifact, dependencies)) {
            audit.runEarly(early, registry, installed, logRoot);
            audit.runMain(main, mainArtifact, registry, installed);
        }
        System.out.println("Итого: пройдено " + audit.passed + ", ошибок " + audit.failed);
        if (audit.failed != 0)
            System.exit(1);
    }

    private static List<Target> readTargets(Path file) throws IOException {
        var targets = new ArrayList<Target>();
        for (String line : Files.readAllLines(file)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 4 || !Set.of("early", "main").contains(fields[0])
                || Arrays.stream(fields).anyMatch(String::isBlank))
                throw new IOException("Неверная строка реестра: " + line);
            var target = new Target(fields[0], fields[1], List.of(fields[2].split("\\|", -1)), fields[3]);
            if (target.classes().stream().anyMatch(String::isBlank) || targets.contains(target))
                throw new IOException("Пустой класс или дубликат цели: " + line);
            targets.add(target);
        }
        if (targets.isEmpty())
            throw new IOException("Реестр пуст: " + file);
        return targets;
    }

    private static List<Installed> readProfile(Path profile) throws IOException {
        Path base = profile.toAbsolutePath().getParent().getParent().getParent();
        var bundles = new ArrayList<Installed>();
        for (String line : Files.readAllLines(profile, StandardCharsets.UTF_8)) {
            line = line.replaceFirst("^\uFEFF", "");
            if (line.isBlank() || line.startsWith("#"))
                continue;
            String[] fields = line.split(",", 5);
            if (fields.length != 5)
                throw new IOException("Неверная строка bundles.info: " + line);
            String location = fields[2].replaceFirst("^reference:", "");
            Path path = location.startsWith("file:")
                ? Path.of(URI.create(location.replace(" ", "%20"))) : base.resolve(location);
            bundles.add(new Installed(fields[0], Version.parseVersion(fields[1]), path));
        }
        return bundles;
    }

    private static URLClassLoader loader(Path artifact, List<Installed> dependencies) throws IOException {
        var paths = new ArrayList<URL>();
        paths.add(artifact.toUri().toURL());
        for (var bundle : dependencies) {
            if (!bundle.id().startsWith("tormozit.") && Files.exists(bundle.path()))
                paths.add(bundle.path().toUri().toURL());
        }
        return new URLClassLoader(paths.toArray(URL[]::new), WeavingAudit.class.getClassLoader());
    }

    private void runEarly(ClassLoader loader, List<Target> registry, List<Installed> installed,
        Path logRoot) throws Exception {
        var hooks = new LinkedHashMap<String, WeavingHook>();
        Bundle ownBundle = osgiBundle("tormozit.comfort.early", Version.emptyVersion);
        BundleContext context = (BundleContext)Proxy.newProxyInstance(BundleContext.class.getClassLoader(),
            new Class<?>[] {BundleContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "registerService" -> {
                    if (args[1] instanceof WeavingHook hook)
                        addHook(hooks, hook);
                    yield null;
                }
                case "getBundle" -> ownBundle;
                case "getDataFile" -> logRoot.resolve((String)args[0]).toFile();
                default -> null;
            });
        Class<?> type = loader.loadClass("tormozit.early.Activator");
        try {
            type.getDeclaredMethod("diagnosticPath", String.class);
        } catch (NoSuchMethodException e) {
            failure("УСТАРЕВШАЯ СБОРКА", "tormozit.comfort.early", "Пересоберите ранний бандл в PDE.");
            return;
        }
        var activator = (BundleActivator)type.getConstructor().newInstance();
        activator.start(context);
        try {
            audit("early", hooks, registry, installed);
            var report = (Supplier<?>)System.getProperties().get("tormozit.weaving.failures");
            if (report != null)
                ((String)report.get()).lines().forEach(line -> System.out.println("ОТЧЁТ | " + line));
        } finally {
            activator.stop(context);
        }
    }

    private void runMain(ClassLoader loader, Path artifact, List<Target> registry, List<Installed> installed)
        throws IOException {
        var hooks = new LinkedHashMap<String, WeavingHook>();
        if (Files.isDirectory(artifact)) {
            try (var paths = Files.walk(artifact.resolve("tormozit"))) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".class")).sorted().toList())
                    loadHook(loader, Files.readAllBytes(path), hooks);
            }
        } else {
            try (var zip = new ZipFile(artifact.toFile())) {
                for (var entry : zip.stream().filter(item -> item.getName().startsWith("tormozit/")
                        && item.getName().endsWith(".class")).sorted(Comparator.comparing(item -> item.getName())).toList()) {
                    try (var input = zip.getInputStream(entry)) {
                        loadHook(loader, input.readAllBytes(), hooks);
                    }
                }
            }
        }
        audit("main", hooks, registry, installed);
    }

    private void loadHook(ClassLoader loader, byte[] bytes, Map<String, WeavingHook> hooks) {
        var reader = new ClassReader(bytes);
        if (!List.of(reader.getInterfaces()).contains("org/osgi/framework/hooks/weaving/WeavingHook")
            || (reader.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0)
            return;
        String name = reader.getClassName().replace('/', '.');
        try {
            var constructor = loader.loadClass(name).getDeclaredConstructor();
            constructor.setAccessible(true);
            addHook(hooks, (WeavingHook)constructor.newInstance());
        } catch (ReflectiveOperationException | LinkageError e) {
            failure("ОБРАБОТЧИК", name, e.toString());
        }
    }

    private void addHook(Map<String, WeavingHook> hooks, WeavingHook hook) {
        String name = hook.getClass().getName();
        if (hooks.putIfAbsent(name, hook) != null)
            failure("ДУБЛИКАТ ОБРАБОТЧИКА", name, "");
    }

    private void audit(String stage, Map<String, WeavingHook> hooks, List<Target> registry,
        List<Installed> installed) throws IOException {
        var expectedHooks = new HashSet<String>();
        var targets = new LinkedHashMap<Resolved, Set<String>>();
        for (Target target : registry) {
            if (!target.stage().equals(stage))
                continue;
            expectedHooks.add(target.hook());
            if (!hooks.containsKey(target.hook())) {
                failure("НЕТ ОБРАБОТЧИКА", target.hook(), target.classes().toString());
                continue;
            }
            var bundles = installed.stream().filter(bundle -> bundle.id().equals(target.bundle())).toList();
            if (bundles.isEmpty()) {
                failure("НЕТ БАНДЛА", target.bundle(), target.classes().toString());
                continue;
            }
            for (Installed bundle : bundles)
                resolve(target, bundle, targets);
        }
        for (String name : hooks.keySet())
            if (!expectedHooks.contains(name))
                failure("НЕТ В РЕЕСТРЕ", name, stage);
        System.out.println(stage + " | обработчиков " + hooks.size());
        for (var entry : targets.entrySet()) {
            var key = entry.getKey();
            var woven = new AuditClass(key, readClass(key.bundle().path(), key.name()));
            for (var hook : hooks.entrySet()) {
                if (!entry.getValue().contains(hook.getKey()))
                    continue;
                check(woven, hook.getValue());
            }
        }
    }

    private void resolve(Target target, Installed bundle, Map<Resolved, Set<String>> targets) throws IOException {
        String bundleLabel = bundle.id() + " " + bundle.version();
        if (!Files.exists(bundle.path())) {
            failure("НЕТ ФАЙЛА БАНДЛА", bundleLabel, bundle.path().toString());
            return;
        }
        var matching = new ArrayList<String>();
        for (String name : target.classes())
            if (readClass(bundle.path(), name) != null)
                matching.add(name);
        if (matching.size() != 1) {
            failure(matching.isEmpty() ? "НЕТ КЛАССА" : "НЕОДНОЗНАЧНАЯ ЦЕЛЬ",
                bundleLabel, target.classes() + " | " + target.hook());
            return;
        }
        var key = new Resolved(bundle, matching.get(0));
        if (!targets.computeIfAbsent(key, unused -> new HashSet<>()).add(target.hook()))
            failure("ДУБЛИКАТ ЦЕЛИ", matching.get(0), target.hook());
    }

    private void check(AuditClass woven, WeavingHook hook) {
        String label = woven.target.bundle().id() + " " + woven.target.bundle().version()
            + " | " + woven.target.name() + " | " + hook.getClass().getName();
        byte[] original = woven.bytes;
        try {
            hook.weave(woven);
            if (Arrays.equals(original, woven.bytes)) {
                failure("НЕ СРАБОТАЛО", label, "байткод не изменился");
                return;
            }
            var after = readNode(woven.bytes);
            var methods = changedMethods(readNode(original), after);
            if (methods.isEmpty()) {
                woven.bytes = original;
                failure("НЕ СРАБОТАЛО", label, "байткод методов не изменился");
                return;
            }
            for (MethodNode method : after.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
            }
            passed++;
            System.out.println("OK | " + label + " | " + String.join(", ", methods));
        } catch (Exception | LinkageError failure) {
            woven.bytes = original;
            failure("ОШИБКА БАЙТКОДА", label, failure.toString());
        }
    }

    private static ClassNode readNode(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static List<String> changedMethods(ClassNode before, ClassNode after) {
        var originals = new LinkedHashMap<String, List<Object>>();
        for (var method : before.methods)
            originals.put(method.name + method.desc, methodText(method));
        var changed = new ArrayList<String>();
        for (var method : after.methods) {
            String key = method.name + method.desc;
            if (!methodText(method).equals(originals.remove(key)))
                changed.add(key);
        }
        changed.addAll(originals.keySet());
        return changed;
    }

    private static List<Object> methodText(MethodNode method) {
        var text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        return text.getText();
    }

    private void failure(String status, String target, String details) {
        failed++;
        System.out.println(status + " | " + target + " | " + details);
    }

    private static byte[] readClass(Path bundle, String name) throws IOException {
        String entry = name.replace('.', '/') + ".class";
        if (Files.isDirectory(bundle)) {
            Path file = bundle.resolve(entry);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (var zip = new ZipFile(bundle.toFile())) {
            var item = zip.getEntry(entry);
            if (item == null)
                return null;
            try (var input = zip.getInputStream(item)) {
                return input.readAllBytes();
            }
        }
    }

    private static Bundle osgiBundle(String id, Version version) {
        return (Bundle)Proxy.newProxyInstance(Bundle.class.getClassLoader(), new Class<?>[] {Bundle.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getSymbolicName" -> id;
                case "getVersion" -> version;
                default -> null;
            });
    }

    private static final class AuditClass implements WovenClass {
        private final Resolved target;
        private final BundleWiring wiring;
        private final List<String> imports = new ArrayList<>();
        private byte[] bytes;

        AuditClass(Resolved target, byte[] bytes) {
            this.target = target;
            this.bytes = bytes;
            Bundle bundle = osgiBundle(target.bundle().id(), target.bundle().version());
            wiring = (BundleWiring)Proxy.newProxyInstance(BundleWiring.class.getClassLoader(),
                new Class<?>[] {BundleWiring.class}, (proxy, method, args) ->
                    method.getName().equals("getBundle") ? bundle : null);
        }

        public byte[] getBytes() { return bytes; }
        public void setBytes(byte[] replacement) { bytes = replacement; }
        public List<String> getDynamicImports() { return imports; }
        public boolean isWeavingComplete() { return false; }
        public String getClassName() { return target.name(); }
        public ProtectionDomain getProtectionDomain() { return null; }
        public Class<?> getDefinedClass() { return null; }
        public BundleWiring getBundleWiring() { return wiring; }
        public int getState() { return TRANSFORMING; }
    }
}
