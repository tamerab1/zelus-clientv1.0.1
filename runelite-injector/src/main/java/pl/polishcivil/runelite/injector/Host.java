package pl.polishcivil.runelite.injector;

import java.util.List;
import java.util.function.Function;
import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import pl.polishcivil.runelite.injector.Injector.ClassGroup;

/**
 * Host
 */
public class Host {

	static void apply(ClassGroup group) {
		group.nodes.values().stream().flatMap(it -> {
			return it.methods.stream();
		}).filter(it -> {
			return constants(it.instructions, (cst) -> cst.equals("192.168.1.")).count() != 0;
		}).forEach(it -> {
			it.instructions.clear();
			it.tryCatchBlocks.clear();
			it.instructions.add(new InsnNode(Opcodes.ICONST_1));
			it.instructions.add(new InsnNode(Opcodes.IRETURN));
		});
	}

	static Stream<LdcInsnNode> constants(InsnList list, Function<String, Boolean> constant) {
		return instructions(list).filter(it -> {
			if (it instanceof LdcInsnNode && ((LdcInsnNode)it).cst instanceof String) {
				return constant.apply((String) ((LdcInsnNode)it).cst);
			} else {
				return false;
			}
		}).map(it -> (LdcInsnNode) it);
	}

	static Stream<AbstractInsnNode> instructions(InsnList list) {
		return StreamSupport.stream(list.spliterator(), false);
	}
}
