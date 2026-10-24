package pl.polishcivil.runelite.injector.inject.hwid;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import pl.polishcivil.runelite.injector.Injector;
import pl.polishcivil.runelite.injector.Injector.ClassGroup;

/**
 * Replaces the client's machine UUID with our own HWID.
 *
 * The platform-info class is the one containing the placeholder UUID literal. Its main
 * constructor takes the machine UUID as its second-to-last (String) parameter and stores it in
 * a field; that field is found from the bytecode rather than hard-coded (rev 231: uf.ct,
 * rev 241: vo.cd -- same parameter position, same usage in both revisions).
 */
public class HWIDInject {

	public static void apply(ClassGroup group) throws Exception {
		var hwidBytecode = getClassBytes(HWID.class);
		var hwid = Injector.read(hwidBytecode);
		group.add(hwid);
		var platformNode = platformInfoNode(group);
		var platformNodeWriteMethod = platformInit(platformNode);

		var insList = platformNodeWriteMethod.instructions;
		int uuidLocal = uuidParameterLocal(platformNodeWriteMethod);
		for (var ins : insList.toArray()) {
			if (ins instanceof FieldInsnNode fIns && fIns.getOpcode() == Opcodes.PUTFIELD
					&& fIns.owner.equals(platformNode.name) && fIns.desc.equals("Ljava/lang/String;")
					&& previousReal(fIns) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == uuidLocal) {
				var instructions = new InsnList();
				instructions.add(new InsnNode(Opcodes.POP));
				instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pl/polishcivil/runelite/injector/inject/hwid/HWID",
						"hwid", "()Ljava/lang/String;"));
				insList.insertBefore(fIns, instructions);
				System.out.println("HWID injected " + platformNode.name + "." + fIns.name);
				return;
			}
		}
		throw new IllegalStateException("Machine UUID field not found in " + platformNode.name);
	}

	// Local variable slot of the constructor's second-to-last parameter (slot 0 is "this").
	static int uuidParameterLocal(MethodNode method) {
		Type[] args = Type.getArgumentTypes(method.desc);
		int index = args.length - 2;
		if (index < 0 || !args[index].getDescriptor().equals("Ljava/lang/String;")) {
			throw new IllegalStateException("Unexpected platform-info constructor " + method.desc);
		}
		int slot = 1;
		for (int i = 0; i < index; i++) {
			slot += args[i].getSize();
		}
		return slot;
	}

	static AbstractInsnNode previousReal(AbstractInsnNode ins) {
		AbstractInsnNode p = ins.getPrevious();
		while (p != null && p.getOpcode() == -1) {
			p = p.getPrevious();
		}
		return p;
	}

	static ClassNode platformInfoNode(ClassGroup group) {
		var nodes = group.nodes.values().stream().filter(it -> {
			return it.methods.stream().anyMatch(m -> Injector.hasLdc(m, "12345678-0000-0000-0000-123456789012"));
		}).collect(Collectors.toList());

		if (nodes.size() != 1) {
			throw new IllegalStateException();
		}
		return nodes.get(0);
	}

	// The class's main constructor (the longest one ending in String parameters).
	static MethodNode platformInit(ClassNode node) {
		return node.methods.stream()
				.filter(it -> it.name.equals("<init>") && it.desc.endsWith("Ljava/lang/String;)V"))
				.max((a, b) -> Integer.compare(Type.getArgumentTypes(a.desc).length, Type.getArgumentTypes(b.desc).length))
				.orElseThrow();
	}

	public static byte[] getClassBytes(Class<?> clazz) throws IOException {
		String className = clazz.getName();
		String classAsPath = className.replace('.', '/') + ".class";
		try (InputStream is = clazz.getClassLoader().getResourceAsStream(classAsPath)) {
			if (is == null) {
				throw new IOException("Could not find class file for " + className);
			}
			return is.readAllBytes();
		}
	}
}
