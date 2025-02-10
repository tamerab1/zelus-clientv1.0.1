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
public class ClanChatCount {

	static void apply(ClassGroup group) {
		group.nodes.values().stream()
			.filter(it -> it.name.equals("so"))
			.flatMap(it -> {
			return it.methods.stream().filter(m -> m.name.contains("init"));
		}).forEach(method -> {
			constants(method.instructions, (cst) -> cst == 500).forEach(ins -> {
				ins.operand = 2048;
			});
		});
	}

	static Stream<IntInsnNode> constants(InsnList list, Function<Integer, Boolean> constant) {
		return instructions(list).filter(it -> {
			if (it.getOpcode() == Opcodes.SIPUSH && it instanceof IntInsnNode v) {
				return constant.apply(v.operand);
			}
			return false;
		}).map(it -> (IntInsnNode) it);
	}

	static Stream<AbstractInsnNode> instructions(InsnList list) {
		return StreamSupport.stream(list.spliterator(), false);
	}
}
