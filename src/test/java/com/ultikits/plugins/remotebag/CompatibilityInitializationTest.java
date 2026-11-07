package com.ultikits.plugins.remotebag;

import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.utils.PackageScanUtils;
import net.bytebuddy.jar.asm.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Compatibility before module activation")
class CompatibilityInitializationTest {
    private static final String PACKAGE = "com.ultikits.plugins.remotebag";
    private static final String PREFIX = PACKAGE.replace('.', '/') + "/";
    private static final String VIEW = "org/bukkit/inventory/InventoryView";
    private static final String SITE = PREFIX + "gui/RemoteBagContentGUI#isPageOpenBy(Ljava/util/UUID;ILjava/util/UUID;)Z";
    private static final String REOPEN_SITE = PREFIX + "gui/RemoteBagContentGUI#reopenRefusedClose()V";

    @Test
    @DisplayName("Released framework scan initializes every top-level module class with class-shaped InventoryView")
    void scanOnOldApiShape() throws Exception {
        Path classes = Paths.get(UltiRemoteBag.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        java.util.logging.Logger log = org.mockito.Mockito.mock(java.util.logging.Logger.class);
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);
             OldShapeLoader loader = new OldShapeLoader(classes.toUri().toURL())) {
            bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(log);
            assertThat(loader.loadClass(VIEW.replace('/', '.')).isInterface()).isFalse();
            Set<String> expected;
            try (Stream<Path> paths = Files.walk(classes.resolve(PREFIX))) {
                expected = paths.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().contains("$"))
                        .map(p -> classes.relativize(p).toString().replace('/', '.').replace(".class", ""))
                        .collect(Collectors.toSet());
            }
            assertThat(expected).contains(PACKAGE + ".gui.RemoteBagContentGUI", PACKAGE + ".UltiRemoteBag");
            // The released 6.3.0 scanner uses Class.forName(..., true, loader), before annotation filtering.
            Set<Class<?>> modules = PackageScanUtils.scanAnnotatedClasses(UltiToolsModule.class, PACKAGE, loader);
            assertThat(modules).extracting(Class::getName).containsExactly(PACKAGE + ".UltiRemoteBag");
            org.mockito.Mockito.verifyNoInteractions(log);
            assertThat(loader.loaded).containsAll(expected);
            for (String name : expected) {
                assertThat(Class.forName(name, true, loader).getClassLoader()).isSameAs(loader);
            }
        }
    }

    @Test
    @DisplayName("No constructor, class initializer, PostConstruct or configuration path reaches the affected call")
    void noInitializationPathExecutesAffectedCall() throws Exception {
        Path classes = Paths.get(UltiRemoteBag.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, Set<String>> calls = new HashMap<>();
        Set<String> roots = new HashSet<>();
        Set<String> affected = new HashSet<>();
        try (Stream<Path> paths = Files.walk(classes.resolve(PREFIX))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())) {
                new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
                    private String owner;
                    @Override public void visit(int v, int a, String n, String s, String p, String[] i) { owner = n; }
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        String method = owner + "#" + name + desc;
                        if (name.equals("<init>") || name.equals("<clinit>") || owner.startsWith(PREFIX + "config/")) {
                            roots.add(method);
                        }
                        Set<String> edges = calls.computeIfAbsent(method, k -> new HashSet<>());
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                if (descriptor.endsWith("/PostConstruct;")) { roots.add(method); }
                                return null;
                            }
                            @Override public void visitMethodInsn(int op, String target, String n, String d, boolean itf) {
                                edges.add(target + "#" + n + d);
                                if (target.equals(VIEW)) { affected.add(method); }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        // Positive control: require both InventoryView callers, including manual-save close recovery.
        assertThat(affected).containsExactlyInAnyOrder(SITE, REOPEN_SITE);
        assertThat(roots).anyMatch(m -> m.contains("service/BagEditClaimService#<init>"));
        assertThat(roots).anyMatch(m -> m.contains("config/RemoteBagConfig#<init>"));
        Set<String> reachable = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String method = todo.removeFirst();
            if (reachable.add(method)) { todo.addAll(calls.getOrDefault(method, Collections.emptySet())); }
        }
        assertThat(reachable).doesNotContainAnyElementsOf(affected);
    }

    /** Child-first only for module bytes and a minimal old-API-shaped type, not a compatibility rewriter. */
    private static final class OldShapeLoader extends URLClassLoader {
        private final Set<String> loaded = new HashSet<>();
        OldShapeLoader(URL classes) {
            // Hide test-output URLs from Guava's recursive scan while delegating framework/API types.
            super(new URL[] {classes}, new ClassLoader(null) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    return CompatibilityInitializationTest.class.getClassLoader().loadClass(name);
                }
            });
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PACKAGE + ".") && !name.equals(VIEW.replace('/', '.'))) {
                return super.loadClass(name, resolve);
            }
            Class<?> result = findLoadedClass(name);
            if (result == null) {
                if (name.equals(VIEW.replace('/', '.'))) {
                    ClassWriter writer = new ClassWriter(0);
                    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, VIEW, null, "java/lang/Object", null);
                    writer.visitEnd();
                    byte[] bytes = writer.toByteArray();
                    result = defineClass(name, bytes, 0, bytes.length);
                } else {
                    result = findClass(name);
                    loaded.add(name);
                }
            }
            if (resolve) { resolveClass(result); }
            return result;
        }
    }
}
