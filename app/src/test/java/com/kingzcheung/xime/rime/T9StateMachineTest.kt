package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * T9StateMachine 单元测试
 *
 * 覆盖三态状态机（IDLE/INPUT/SELECTION）的所有状态转换规则。
 */
class T9StateMachineTest {

    private fun createMachine() = T9StateMachine()

    // ── 初始状态 ──

    @Test
    fun `initial state is IDLE`() {
        val sm = createMachine()
        assertEquals(T9StateMachine.State.IDLE, sm.state)
        assertTrue(sm.isIdle)
        assertFalse(sm.isInput)
        assertFalse(sm.isSelection)
        assertFalse(sm.hasSelection)
    }

    // ── enterInput ──

    @Test
    fun `enterInput transitions IDLE to INPUT`() {
        val sm = createMachine()
        sm.enterInput()
        assertEquals(T9StateMachine.State.INPUT, sm.state)
        assertTrue(sm.isInput)
        assertFalse(sm.isIdle)
        assertFalse(sm.isSelection)
        assertNull(sm.selectedOption)
        assertNull(sm.selectionCandidateDigits)
    }

    @Test
    fun `enterInput from INPUT stays INPUT`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterInput()
        assertEquals(T9StateMachine.State.INPUT, sm.state)
    }

    // ── enterSelection ──

    @Test
    fun `enterSelection transitions to SELECTION with option and digits`() {
        val sm = createMachine()
        sm.enterInput()
        val option = T9PinyinMap.SyllableOption("ji", 2)
        sm.enterSelection(option, "54")
        assertEquals(T9StateMachine.State.SELECTION, sm.state)
        assertTrue(sm.isSelection)
        assertTrue(sm.hasSelection)
        assertEquals(option, sm.selectedOption)
        assertEquals("54", sm.selectionCandidateDigits)
    }

    @Test
    fun `enterSelection replaces previous selection`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54")
        val newOption = T9PinyinMap.SyllableOption("li", 2)
        sm.enterSelection(newOption, "54")
        assertEquals(newOption, sm.selectedOption)
        assertEquals("54", sm.selectionCandidateDigits)
    }

    // ── exitSelection ──

    @Test
    fun `exitSelection transitions SELECTION to INPUT`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54")
        sm.exitSelection()
        assertEquals(T9StateMachine.State.INPUT, sm.state)
        assertNull(sm.selectedOption)
        assertNull(sm.selectionCandidateDigits)
        assertFalse(sm.hasSelection)
    }

    @Test
    fun `exitSelection from INPUT is no-op`() {
        val sm = createMachine()
        sm.enterInput()
        sm.exitSelection()
        assertEquals(T9StateMachine.State.INPUT, sm.state)
    }

    // ── enterIdle ──

    @Test
    fun `enterIdle transitions any state to IDLE`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54")
        sm.enterIdle()
        assertEquals(T9StateMachine.State.IDLE, sm.state)
        assertNull(sm.selectedOption)
        assertNull(sm.selectionCandidateDigits)
    }

    @Test
    fun `enterIdle from INPUT transitions to IDLE`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterIdle()
        assertEquals(T9StateMachine.State.IDLE, sm.state)
    }

    // ── reset ──

    @Test
    fun `reset returns to IDLE from any state`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54")
        sm.reset()
        assertEquals(T9StateMachine.State.IDLE, sm.state)
        assertNull(sm.selectedOption)
        assertNull(sm.selectionCandidateDigits)
    }

    // ── snapshot / restore ──

    @Test
    fun `snapshot captures current state`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54")
        val snap = sm.snapshot()
        assertEquals(T9StateMachine.State.SELECTION, snap.state)
        assertEquals("ji", snap.selectedOption?.pinyin)
        assertEquals("54", snap.selectionCandidateDigits)
    }

    @Test
    fun `restore recovers state from snapshot`() {
        val sm = createMachine()
        sm.enterInput()
        sm.enterSelection(T9PinyinMap.SyllableOption("ji", 2), "54")
        val snap = sm.snapshot()
        sm.enterIdle()
        sm.restore(snap)
        assertEquals(T9StateMachine.State.SELECTION, sm.state)
        assertEquals("ji", sm.selectedOption?.pinyin)
        assertEquals("54", sm.selectionCandidateDigits)
    }

    @Test
    fun `restoreFrom recovers state from raw values`() {
        val sm = createMachine()
        sm.enterIdle()
        val option = T9PinyinMap.SyllableOption("gua", 3)
        sm.restoreFrom(T9StateMachine.State.SELECTION, option, "482")
        assertEquals(T9StateMachine.State.SELECTION, sm.state)
        assertEquals("gua", sm.selectedOption?.pinyin)
        assertEquals("482", sm.selectionCandidateDigits)
    }

    // ── 便捷查询 ──

    @Test
    fun `hasSelection false when SELECTION but no selectedOption`() {
        val sm = createMachine()
        sm.enterInput()
        // 直接设置 state 为 SELECTION 但不设置 selectedOption
        sm.restoreFrom(T9StateMachine.State.SELECTION, null, null)
        assertTrue(sm.isSelection)
        assertFalse(sm.hasSelection)
    }
}