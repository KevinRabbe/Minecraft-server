package io.github.kevinrabbe.minecraftserver.common.progression;

public final class SkillProgressionException extends RuntimeException {
    public SkillProgressionException(String message) {
        super(message);
    }

    public SkillProgressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
