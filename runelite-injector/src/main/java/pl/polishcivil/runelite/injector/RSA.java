package pl.polishcivil.runelite.injector;

import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import pl.polishcivil.runelite.injector.Injector.ClassGroup;

/**
 * Host
 */
public class RSA {
	// Must be the hex form of the SAME modulus as the game server's
	// RSA_MODULUS (server.properties, decimal there). Was a leftover value
	// from the codebase this was forked from -- didn't match the Zelus
	// server's actual generated keypair, so every login block the client
	// encrypted failed server-side decryption ("Invalid RSA check '0'").
	private static String RSA = "d43d76061837561686b5387f9c3f6d290f5cc96ab71834728f8652cea050e68d7f50992377d5a6dddc098d8fd82c0513e391791c7a7e163a81be0e04764ec9e1ea7662b9fa0bd2c5b678ed9cc5cf42d03869d83af59961e6918d77d42b4ad5f166a3dc42f68b1e9dab94692084f8df5eb9f5ef8679824eedaa533172e61c705d";

	static void apply(ClassGroup group) {
		group.nodes.values().stream().flatMap(cls -> {
			return cls.methods.stream().map(it -> new Pair(cls, it));
		}).flatMap(it -> {
			return filter(it);
		}).forEach(it -> {
			if (!it.keyNode.cst.equals("10001")) {
				System.out.println("FOUND RSA " + it.keyNode.cst);
				it.keyNode.cst = RSA;
			}
		});
	}

	static Stream<Result> filter(Pair node) {
		return instructions(node.method.instructions).filter(it -> {
			if (it instanceof MethodInsnNode && ((MethodInsnNode) it).name.equals("<init>")
					&& ((MethodInsnNode) it).owner.endsWith("BigInteger")) {
				var v0 = it.getPrevious();
				var v1 = it.getPrevious().getPrevious();
				return v0.getOpcode() == Opcodes.BIPUSH && v1 instanceof LdcInsnNode;
			} else {
				return false;
			}
		}).map(it -> new Result(node, (LdcInsnNode) it.getPrevious().getPrevious()));
	}

	static Stream<AbstractInsnNode> instructions(InsnList list) {
		return StreamSupport.stream(list.spliterator(), false);
	}

	static class Pair {
		ClassNode cls;
		MethodNode method;

		Pair(ClassNode cls, MethodNode method) {
			this.cls = cls;
			this.method = method;
		}
	}

	static class Result {
		Pair node;
		LdcInsnNode keyNode;

		Result(Pair node, LdcInsnNode keyNode) {
			this.node = node;
			this.keyNode = keyNode;
		}
	}
}
