package pl.polishcivil.runelite.injector;

import java.util.List;
import java.util.stream.Collectors;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import pl.polishcivil.runelite.injector.Injector.ClassGroup;

/**
 * Makes the client's Username "clean name" keep the name as typed (minus formatting tags)
 * instead of the client's normalised form.
 *
 * The Username class and its fields are found by shape instead of by obfuscated name, so this
 * survives revision updates: it is the Comparable class with exactly two String instance fields
 * and two constructors, (String) and (String, LoginType). In those constructors the raw name is
 * the first argument and the clean-name field is the one assigned from a method call
 * (rev 231: xm.aj, rev 241: aae.az).
 */
public class UsernameInject {
	private static final String STRING_DESC = "Ljava/lang/String;";

	static void apply(ClassGroup group) {
		ClassNode owner = findUsernameClass(group);
		String cleanField = findCleanField(owner);
		System.out.println("Username class " + owner.name + ", clean-name field " + cleanField);

		int patched = 0;
		for (MethodNode ctor : constructors(owner)) {
			for (AbstractInsnNode ins : ctor.instructions.toArray()) {
				if (ins instanceof FieldInsnNode fIns && fIns.getOpcode() == Opcodes.PUTFIELD
						&& fIns.owner.equals(owner.name) && fIns.name.equals(cleanField) && fIns.desc.equals(STRING_DESC)) {
					// replace the computed clean name with Text.removeTags(<raw name argument>)
					var insList = new InsnList();
					insList.add(new InsnNode(Opcodes.POP));
					insList.add(new VarInsnNode(Opcodes.ALOAD, 1));
					insList.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/runelite/client/util/Text", "removeTags",
							"(Ljava/lang/String;)Ljava/lang/String;"));
					ctor.instructions.insertBefore(ins, insList);
					patched++;
				}
			}
		}
		if (patched == 0) {
			throw new IllegalStateException("Username clean-name assignment not found in " + owner.name);
		}
		System.out.println("Username injected (" + patched + " constructors)");
	}

	static ClassNode findUsernameClass(ClassGroup group) {
		List<ClassNode> matches = group.nodes.values().stream()
				.filter(c -> c.interfaces.contains("java/lang/Comparable"))
				.filter(c -> stringInstanceFields(c).size() == 2)
				.filter(c -> constructors(c).stream().anyMatch(m -> m.desc.equals("(Ljava/lang/String;)V")))
				.filter(c -> constructors(c).stream().anyMatch(m -> m.desc.matches("\\(Ljava/lang/String;L[^;]+;\\)V")))
				.collect(Collectors.toList());
		if (matches.size() != 1) {
			throw new IllegalStateException("Expected exactly one Username class, found " + matches.size());
		}
		return matches.get(0);
	}

	// The clean-name field is assigned from a method call; the raw-name field straight from ALOAD 1.
	static String findCleanField(ClassNode owner) {
		MethodNode ctor = constructors(owner).stream()
				.filter(m -> m.desc.matches("\\(Ljava/lang/String;L[^;]+;\\)V"))
				.findFirst().orElseThrow();
		String clean = null;
		for (AbstractInsnNode ins : ctor.instructions.toArray()) {
			if (ins instanceof FieldInsnNode fIns && fIns.getOpcode() == Opcodes.PUTFIELD && fIns.desc.equals(STRING_DESC)
					&& fIns.owner.equals(owner.name) && previousReal(ins) instanceof MethodInsnNode) {
				if (clean != null && !clean.equals(fIns.name)) {
					throw new IllegalStateException("Ambiguous clean-name field in " + owner.name);
				}
				clean = fIns.name;
			}
		}
		if (clean == null) {
			throw new IllegalStateException("Clean-name field not found in " + owner.name);
		}
		return clean;
	}

	private static List<FieldNode> stringInstanceFields(ClassNode c) {
		return c.fields.stream()
				.filter(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals(STRING_DESC))
				.collect(Collectors.toList());
	}

	private static List<MethodNode> constructors(ClassNode c) {
		return c.methods.stream().filter(m -> m.name.equals("<init>")).collect(Collectors.toList());
	}

	static AbstractInsnNode previousReal(AbstractInsnNode ins) {
		AbstractInsnNode p = ins.getPrevious();
		while (p != null && p.getOpcode() == -1) {
			p = p.getPrevious();
		}
		return p;
	}
}
