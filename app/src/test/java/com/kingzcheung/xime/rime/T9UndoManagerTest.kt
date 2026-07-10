package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/** T9UndoManager 单元测试 — 命令模式（Phase 3）。 */
class T9UndoManagerTest {

    private fun mgr() = T9UndoManager()
    private fun sm() = T9StateMachine()
    private fun ctx(buf: T9Buffer = T9Buffer.EMPTY, machine: T9StateMachine = sm()) = T9Command.Ctx(
        buffer = buf, leftColumnLocked = false, separatorConsumedDigits = null,
        lastChoiceConsumedDigits = null,
        stateMachine = machine, onRestored = {},
    )

    @Test fun `initial empty`() {
        val m = mgr()
        assertTrue(m.isEmpty()); assertEquals(0, m.size); assertNull(m.peek())
        assertFalse(m.topIsRightCommit)
    }

    @Test fun `push digit command`() {
        val m = mgr()
        m.push(T9Command.DigitPressed("5"))
        assertEquals(1, m.size); assertTrue(m.peek() is T9Command.DigitPressed)
    }

    @Test fun `multiple pushes stack`() {
        val m = mgr()
        m.push(T9Command.DigitPressed("5")); m.push(T9Command.DigitPressed("4"))
        assertEquals(2, m.size)
    }

    @Test fun `pop returns top command`() {
        val m = mgr()
        m.push(T9Command.DigitPressed("5"))
        assertEquals(T9Command.DigitPressed("5"), m.pop())
        assertEquals(0, m.size)
    }

    @Test fun `pop null when empty`() {
        assertNull(mgr().pop())
    }

    @Test fun `digit undo removes last char`() {
        val m = mgr(); val s = sm(); s.enterInput()
        val c = ctx(buf = T9Buffer(digitSequence = "54"))
        m.push(T9Command.DigitPressed("4"))
        m.popAndUndo(c)
        assertEquals("5", c.buffer.toBufferString())
    }

    @Test fun `leftChoice undo restores full state`() {
        val m = mgr(); val s = sm()
        s.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54", "")
        val c = ctx(
            buf = T9Buffer(digitSequence = "54482", selections = listOf(T9Buffer.Selection("ji", 2)), consumedCount = 2),
            machine = s,
        )
        m.push(T9Command.LeftChoice(
            prevBuffer = T9Buffer(digitSequence = "54482"),
            prevSeparatorConsumedDigits = null, prevLeftColumnLocked = false,
            prevSelectedOption = null, prevSelectionCandidateDigits = null,
            prevConfirmedPinyin = "", prevSelectionHistory = emptyList(),
        ))
        m.popAndUndo(c)
        assertEquals("54482", c.buffer.toBufferString())
        assertEquals(T9StateMachine.State.INPUT, s.state)
    }

    @Test fun `rightCommit undo rebuilds buffer from stateMachine`() {
        val m = mgr(); val s = sm()
        s.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54", "")
        val c = ctx(buf = T9Buffer(digitSequence = "54482"), machine = s)
        m.push(T9Command.RightCommit(
            prevBuffer = T9Buffer(
                digitSequence = "54482",
                selections = listOf(T9Buffer.Selection("ji", 2)),
                consumedCount = 2,
                totalDigitsEntered = 5,
            ),
            prevSeparatorConsumedDigits = null, prevLastChoiceConsumedDigits = null,
            prevLeftColumnLocked = false, prevSelectedOption = null, prevSelectionCandidateDigits = null,
            prevConfirmedPinyin = "", prevSelectionHistory = s.selectionHistory.toList(),
        ))
        m.popAndUndo(c)
        // undo 后：stateMachine 恢复到 INPUT 态，selectionHistory = [ji(2)]
        // T9Buffer：digitSequence="54482", selections=[ji(2)], consumedCount=2 → "ji'482"
        assertEquals("ji'482", c.buffer.toBufferString())
        assertEquals(T9StateMachine.State.INPUT, s.state)
    }

    @Test fun `clear removes all`() {
        val m = mgr()
        m.push(T9Command.DigitPressed("5"))
        m.clear()
        assertTrue(m.isEmpty())
    }

    @Test fun `topIsRightCommit`() {
        val m = mgr()
        m.push(T9Command.RightCommit(T9Buffer.EMPTY, null, null, false, null, null))
        assertTrue(m.topIsRightCommit); assertTrue(m.hasPendingRightCommit())
    }

    @Test fun `separator undo restores buffer`() {
        val m = mgr(); val s = sm()
        val c = ctx(
            buf = T9Buffer(digitSequence = "54", selections = listOf(T9Buffer.Selection("j", 1)), consumedCount = 1),
            machine = s,
        )
        m.push(T9Command.Separator(prevBuffer = T9Buffer(digitSequence = "54"), prevSeparatorConsumedDigits = null))
        m.popAndUndo(c)
        assertEquals("54", c.buffer.toBufferString())
    }
}
