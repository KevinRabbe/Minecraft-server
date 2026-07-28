package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyClanWarControlPointStateTest {
    @Test
    void uncontestedPresenceAccumulatesAndContestedOrEmptyPointPauses() {
        LegacyClanWarControlPointState state = new LegacyClanWarControlPointState(3);

        assertNull(state.advance(1, 0));
        assertEquals(1, state.getChallengerProgress());
        assertNull(state.advance(1, 1));
        assertEquals(1, state.getChallengerProgress());
        assertEquals(0, state.getDefenderProgress());
        assertNull(state.advance(0, 0));
        assertEquals(1, state.getChallengerProgress());

        assertNull(state.advance(0, 2));
        assertEquals(1, state.getDefenderProgress());
        assertNull(state.advance(1, 0));
        assertEquals(2, state.getChallengerProgress());
        assertEquals(LegacyClanWarControlPointState.Side.CHALLENGER, state.advance(4, 0));
    }

    @Test
    void winnerIsTerminalAndCannotBeOverwritten() {
        LegacyClanWarControlPointState state = new LegacyClanWarControlPointState(1);
        assertEquals(LegacyClanWarControlPointState.Side.DEFENDER, state.advance(0, 1));
        assertEquals(LegacyClanWarControlPointState.Side.DEFENDER, state.advance(100, 0));
        assertEquals(0, state.getChallengerProgress());
        assertEquals(1, state.getDefenderProgress());
    }

    @Test
    void rejectsInvalidCountsAndThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyClanWarControlPointState(0));
        LegacyClanWarControlPointState state = new LegacyClanWarControlPointState(2);
        assertThrows(IllegalArgumentException.class, () -> state.advance(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> state.advance(0, -1));
    }
}
