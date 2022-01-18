package net.covers1624.fastremap;

import net.minecraftforge.srgutils.IMappingFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Created by covers1624 on 17/9/21.
 */
public class ASMRemapper extends Remapper {

    private static final String[] EMPTY = new String[0];

    private final Path root;
    private final IMappingFile mappings;
    private final Map<String, String[]> hierarchy = new HashMap<>();
    private final Map<String, String[]> mergedHierarchy = new HashMap<>();
    private final Map<String, Map<String, String>> fieldCache = new HashMap<>();
    private final Map<String, Map<String, String>> methodCache = new HashMap<>();

    public ASMRemapper(Path root, IMappingFile mappings) {
        this.root = root;
        this.mappings = mappings;
        for (IMappingFile.IClass clazz : mappings.getClasses()) {
            fieldCache.put(clazz.getOriginal(), new HashMap<>());
            methodCache.put(clazz.getOriginal(), new HashMap<>());
        }
    }

    @Override
    public String map(String internalName) {
        IMappingFile.IClass clazz = mappings.getClass(internalName);
        return clazz != null ? clazz.getMapped() : internalName;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        IMappingFile.IClass clazz = mappings.getClass(owner);
        if (clazz == null) return name;

        // Hotwire quick lookup.
        Map<String, String> cache = fieldCache.get(owner);
        assert cache != null;
        String existing = cache.get(name + descriptor);
        if (existing != null) return existing;

        String ret = name;
        IMappingFile.IField field = clazz.getField(name);
        if (field == null) {
            String[] parents = getParents(owner);
            for (String parent : parents) {
                String mapped = mapFieldName(parent, name, descriptor);
                if (!mapped.equals(name)) {
                    ret = mapped;
                    break;
                }
            }
        } else {
            ret = field.getMapped();
        }

        cache.put(name + descriptor, ret);
        return ret;
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        IMappingFile.IClass clazz = mappings.getClass(owner);
        if (clazz == null) return name;

        // Hotwire quick lookup.
        Map<String, String> cache = methodCache.get(owner);
        assert cache != null;
        String existing = cache.get(name + descriptor);
        if (existing != null) return existing;

        String ret = name;
        IMappingFile.IMethod method = clazz.getMethod(name, descriptor);
        if (method == null) {
            String[] parents = getParents(owner);
            for (String parent : parents) {
                String mapped = mapMethodName(parent, name, descriptor);
                if (!mapped.equals(name)) {
                    ret = mapped;
                    break;
                }
            }
        } else {
            ret = method.getMapped();
        }

        cache.put(name + descriptor, ret);
        return ret;
    }

    private String[] getParents(String cName) {
        String[] existing = mergedHierarchy.get(cName);
        if (existing != null) return existing;

        Set<String> allSuperTypes = new HashSet<>();
        String[] directSuperTypes = getDirectSuperTypes(cName);
        for (String directSuper : directSuperTypes) {
            if (allSuperTypes.add(directSuper)) {
                Collections.addAll(allSuperTypes, getParents(directSuper));
            }
        }

        String[] hierarchy = allSuperTypes.toArray(new String[0]);
        mergedHierarchy.put(cName, hierarchy);
        return hierarchy;
    }

    private String[] getDirectSuperTypes(String cName) {
        String[] directSuperTypes = hierarchy.get(cName);
        if (directSuperTypes != null) return directSuperTypes;

        try (InputStream is = Files.newInputStream(root.resolve(cName + ".class"))) {
            directSuperTypes = extractSupertypes(new ClassReader(is));
        } catch (IOException e) {
            directSuperTypes = EMPTY;
        }
        hierarchy.put(cName, directSuperTypes);
        return directSuperTypes;
    }

    public void collectDirectSupertypes(ClassReader reader) {
        String cName = reader.getClassName();
        if (!hierarchy.containsKey(cName)) {
            hierarchy.put(cName, extractSupertypes(reader));
        }
    }

    private String[] extractSupertypes(ClassReader reader) {
        String superName = reader.getSuperName();
        String[] interfaces = reader.getInterfaces();

        // No super, just return interfaces.
        if (superName == null) return interfaces;
        // No interfaces, just return the super
        if (interfaces.length == 0) return new String[] { superName };
        String[] parents = new String[interfaces.length + 1];
        parents[0] = superName;
        System.arraycopy(interfaces, 0, parents, 1, interfaces.length);
        return parents;
    }
}
