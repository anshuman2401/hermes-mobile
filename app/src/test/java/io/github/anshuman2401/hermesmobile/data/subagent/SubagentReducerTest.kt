package io.github.anshuman2401.hermesmobile.data.subagent

import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.gateway.json.*
import org.junit.Assert.*
import org.junit.Test

class SubagentReducerTest {
    private fun child(status: SubagentStatus, id: String = "child") = Subagent(id, "Synthetic task", status)
    @Test fun terminalStateAndOrderSurviveLateEvents() {
        var state = SubagentReducer.event(SubagentState(), child(SubagentStatus.QUEUED), 100)
        state = SubagentReducer.event(state, child(SubagentStatus.RUNNING, "nested").copy(parentId = "child"), 200)
        state = SubagentReducer.event(state, child(SubagentStatus.FAILED).copy(activity = listOf("Failed")), 300)
        state = SubagentReducer.event(state, child(SubagentStatus.RUNNING), 400)
        assertEquals(listOf("child","nested"), state.children.map { it.id })
        assertEquals(SubagentStatus.FAILED, state.children.first().status)
        assertEquals(100L, state.children.first().startedAtMillis)
        assertEquals("child", state.children.last().parentId)
    }
    @Test fun missingRosterChildrenNeverBecomeSuccess() {
        val state = SubagentReducer.event(SubagentState(), child(SubagentStatus.RUNNING), 100)
        val next = SubagentReducer.snapshot(state, SubagentRoster(emptyList()), 300)
        assertEquals(SubagentStatus.UNKNOWN, next.children.single().status)
    }
    @Test fun activityIsDeduplicatedAndBoundedAndTerminalSlotsCanBeReused() {
        var state = SubagentState()
        repeat(200) { state = SubagentReducer.event(state, child(SubagentStatus.RUNNING).copy(activity = listOf("Activity $it")), it.toLong()) }
        assertEquals(24, state.children.single().activity.size)
        val before = state.children.single().activity
        state = SubagentReducer.event(state, child(SubagentStatus.RUNNING).copy(activity = listOf("Activity 199")), 201)
        assertEquals(before, state.children.single().activity)
        state = SubagentState()
        repeat(150) { state = SubagentReducer.event(state, child(SubagentStatus.COMPLETED,"child-$it"), 1) }
        assertEquals(128,state.children.size)
        assertTrue(state.retiredIds.contains("child-0"))
        state = SubagentReducer.event(state,child(SubagentStatus.RUNNING,"child-0"),2)
        assertFalse(state.children.any { it.id == "child-0" })
    }
    @Test fun malformedIdentitiesAndUnknownCompletionFailClosed() {
        assertNull(SubagentWireDecoder.child(GatewayJson.parse("""{"subagent_id":" ","goal":"Synthetic"}""")))
        val future = SubagentWireDecoder.child(GatewayJson.parse("""{"subagent_id":"child","status":"running"}"""),"subagent.complete")!!
        assertEquals(SubagentStatus.UNKNOWN,future.status)
        val settled = SubagentReducer.event(SubagentState(),future,1)
        assertEquals(SubagentStatus.UNKNOWN,SubagentReducer.event(settled,child(SubagentStatus.RUNNING),2).children.single().status)
        assertEquals(SubagentStatus.FAILED,SubagentWireDecoder.status("timeout",true))
        assertNull(SubagentWireDecoder.roster(JsonObject()))
        assertNull(SubagentWireDecoder.tail(GatewayJson.parse("""{"available":true}""")))
    }
    @Test fun snapshotMakesRoomForNewLiveChildrenWithoutRevivingRetiredIds() {
        val full = SubagentState(children = (0 until 128).map { child(SubagentStatus.COMPLETED,"old-$it") })
        val next = SubagentReducer.snapshot(full,SubagentRoster(listOf(child(SubagentStatus.RUNNING,"new"))),2)
        assertEquals(128,next.children.size)
        assertEquals("new",next.children.last().id)
        assertTrue("old-0" in next.retiredIds)
    }
    @Test fun unicodeBoundsAndCredentialRedactionApplyBeforeDisplay() {
        val long = "😀".repeat(20000)
        val tail = SubagentWireDecoder.tail(JsonObject("available" to JsonBoolean(true),"text" to JsonString("password=synthetic-value " + long)))!!
        assertTrue(tail.truncated)
        assertTrue(tail.text.toByteArray(Charsets.UTF_8).size <= 16384)
        assertFalse(tail.text.contains("synthetic-value"))
        assertFalse(tail.text.last().isHighSurrogate())
        assertEquals(256,SubagentText.preview(long).codePointCount(0,SubagentText.preview(long).length))
    }
}
