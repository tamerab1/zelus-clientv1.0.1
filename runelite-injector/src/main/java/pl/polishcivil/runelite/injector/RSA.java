package pl.polishcivil.runelite.injector;

import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import pl.polishcivil.runelite.injector.Injector.ClassGroup;

/**
 * Host
 */
public class RSA {
	// Hex form of the SAME modulus as the game server's RSA_MODULUS (server.properties,
	// decimal there). Production/VPS keypair, matches jav_config.ws's play.zelusrsps.com target.
	private static final String PROD_RSA = "d43d76061837561686b5387f9c3f6d290f5cc96ab71834728f8652cea050e68d7f50992377d5a6dddc098d8fd82c0513e391791c7a7e163a81be0e04764ec9e1ea7662b9fa0bd2c5b678ed9cc5cf42d03869d83af59961e6918d77d42b4ad5f166a3dc42f68b1e9dab94692084f8df5eb9f5ef8679824eedaa533172e61c705d";

	// This project's local dev server's own RSA_MODULUS (kronos-server/server.properties, hex
	// via `python3 -c "print(hex(int(RSA_MODULUS))[2:])"`), matches jav_config_local.ws's
	// zelus.test target. Never the same keypair as production -- these are two genuinely
	// different servers with different keys, not a toggle on one shared value.
	private static final String LOCAL_RSA = "981d7e7e92f6676be104d3f7ff1b11e8f654534512f4d7b94ba6c5b7cfeffabf0eb2798e9b5437da5a50b73413491a8b47d5d3a091709d7aafba2caf0ba66760a54f654801ed2e3fcafaafb25db7cce7b96f30cc213555689026b523b1d1a27aa6d5ab4cbe1b1e7fe5c3b7b93006060c334db3c8aa2c5a85f83f3dd50b6a2ff9";

	// Opt-in only (-Dzelus.local=true passed to the injector's Gradle invocation) -- a normal
	// build (no property set) always produces the production-targeting client, unchanged.
	private static final String RSA = Boolean.getBoolean("zelus.local") ? LOCAL_RSA : PROD_RSA;

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
