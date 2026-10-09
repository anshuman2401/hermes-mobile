package com.hermes.bot.feature.chat

import com.hermes.bot.data.subagent.*
import org.junit.Assert.*
import org.junit.Test

class SubagentPresentationTest {
    private val args = mapOf("tasks" to listOf(mapOf("goal" to "Synthetic A"),mapOf("goal" to "Synthetic B")))
    @Test fun taskOrderOutcomesAndDispatchAreDerivedFromStructuredFields() {
        val result = mapOf("results" to listOf(mapOf("status" to "ok"),mapOf("status" to "timeout")))
        val rows = SubagentPresentation.tasks("delegate_task",args,result,"call")
        assertEquals(listOf("Synthetic A","Synthetic B"),rows.map { it.goal })
        assertEquals(listOf(SubagentStatus.COMPLETED,SubagentStatus.FAILED),rows.map { it.status })
        assertTrue(rows.none { it.observed })
        assertTrue(SubagentPresentation.tasks("delegate_task",args,null,"call",true).all { it.status == SubagentStatus.UNKNOWN })
        assertTrue(SubagentPresentation.tasks("delegate_task",args,mapOf("status" to "dispatched"),"call",true).all { it.status == SubagentStatus.DISPATCHED })
        assertTrue(SubagentPresentation.tasks("delegate_task",mapOf("action" to "steer","goal" to "Synthetic"),null,"call").isEmpty())
    }
    @Test fun ambiguousSameGoalNeverAssociatesAControlIdentity() {
        val rows=SubagentPresentation.tasks("delegate_task",mapOf("goal" to "Synthetic"),null,"call")
        val tool=ToolActivity("call","call","delegate_task",ToolStatus.RUNNING,delegatedTasks=rows)
        val live=listOf(Subagent("child","Synthetic",SubagentStatus.RUNNING))
        assertTrue(SubagentPresentation.rows(tool,listOf(tool),live).single().observed)
        val other=tool.copy(id="other",toolId="other")
        assertFalse(SubagentPresentation.rows(tool,listOf(tool,other),live).single().observed)
        assertFalse(SubagentPresentation.rows(tool,listOf(tool),live+live.single().copy(id="other-child")).single().observed)
    }
    @Test fun historicalGoalsCannotAcquireNewIdentitiesButExplicitDispatchIdsCan() {
        val rows=SubagentPresentation.tasks("delegate_task",mapOf("action" to "", "goal" to "Synthetic"),null,"old",true)
        val tool=ToolActivity("old","old","delegate_task",ToolStatus.COMPLETED,delegatedTasks=rows)
        val live=listOf(Subagent("new-child","Synthetic",SubagentStatus.RUNNING))
        assertFalse(SubagentPresentation.rows(tool,listOf(tool),live).single().observed)
        val dispatched=tool.copy(delegatedTasks=SubagentPresentation.tasks("delegate_task",mapOf("goal" to "Synthetic"),mapOf("status" to "dispatched","subagent_ids" to listOf("new-child")),"old",true))
        assertTrue(SubagentPresentation.rows(dispatched,listOf(dispatched),live).single().observed)
        val prior=tool.copy(delegatedTasks=rows.map { it.copy(historical=false) })
        assertFalse(SubagentPresentation.rows(prior,listOf(prior,UserMessage("user","Synthetic")),live).single().observed)
    }
    @Test fun taskProjectionBudgetIsBoundedAndVisible() {
        val tasks=SubagentPresentation.tasks("delegate_task",mapOf("tasks" to (0..127).map { mapOf("goal" to "😀".repeat(500)) }),null,"call")
        assertTrue(tasks.isNotEmpty())
        assertTrue(tasks.sumOf(SubagentPresentation::projectionBytes) <= 4096)
        assertTrue(tasks.last().projectionTruncated)
    }
    @Test fun dedicatedCardsSplitToolGroupsWithoutDuplicatingOrDroppingOrdinarySteps() {
        val ordinary=ToolActivity("ordinary","ordinary","terminal",ToolStatus.COMPLETED)
        val delegation=ToolActivity("call","call","delegate_task",ToolStatus.COMPLETED,delegatedTasks=SubagentPresentation.tasks("delegate_task",args,null,"call",true))
        val block=ToolActivityBlock("block",steps=listOf(ToolBlockStep.Tool(ordinary),ToolBlockStep.Tool(delegation),ToolBlockStep.Tool(ordinary.copy(id="last"))),totalActionCount=3,aggregateTimeMillis=null,aggregateTimeText=null)
        val display=SubagentPresentation.display(listOf(TraceDisplayEntry.BlockCard(block)))
        assertEquals(3,display.size)
        assertEquals("call",(display[1] as TraceDisplayEntry.Raw).entry.id)
        assertEquals(2,display.filterIsInstance<TraceDisplayEntry.BlockCard>().sumOf { it.block.steps.size })
        assertEquals(3,display.map { it.key }.distinct().size)
    }
}
