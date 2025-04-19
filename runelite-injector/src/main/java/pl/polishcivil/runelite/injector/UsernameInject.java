package pl.polishcivil.runelite.injector;

import java.util.Arrays;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import pl.polishcivil.runelite.injector.Injector.ClassGroup;

// TODO(polish) - do i really need it?
public class UsernameInject {
	private static final String USERNAME_OWNER = "wm";
	private static final String USERNAME_NAME = "ab";
	private static final String USERNAME_DESC = "Ljava/lang/String;";

	static void apply(ClassGroup group) {
		group.node(USERNAME_OWNER).methods.forEach(mNode -> {
			var instructions = mNode.instructions;
			Arrays.stream(mNode.instructions.toArray()).forEach(ins -> {
				if (ins instanceof FieldInsnNode fIns && isPutField(fIns)) {
					// var removeTagsCall = new MethodInsnNode(Opcodes.INVOKESTATIC, "net/runelite/client/util/Text", "removeTags", "(Ljava/lang/String;)Ljava/lang/String;");
					// instructions.insertBefore(ins, removeTagsCall);
				}
			});
		});
	}

	private static boolean isPutField(FieldInsnNode fIns) {
		return fIns.name.equals(USERNAME_NAME)
				&& fIns.owner.equals(USERNAME_OWNER)
				&& fIns.desc.equals(USERNAME_DESC)
				&& fIns.getOpcode() == Opcodes.PUTFIELD;
	}
}
