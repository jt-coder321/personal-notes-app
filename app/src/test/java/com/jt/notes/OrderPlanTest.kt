package com.jt.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderPlanTest {
    @Test fun valuesComeBackBiggestFirst() {
        assertEquals(listOf(9L, 7L, 5L), OrderPlan.assign(listOf(5, 9, 7)))
    }

    @Test fun tiesAreBroken() {
        assertEquals(listOf(5L, 4L, 3L), OrderPlan.assign(listOf(5, 5, 5)))
    }

    @Test fun emptyAndSingle() {
        assertEquals(emptyList<Long>(), OrderPlan.assign(emptyList()))
        assertEquals(listOf(42L), OrderPlan.assign(listOf(42)))
    }

    @Test fun movingOneNoteKeepsTheOthersInPlace() {
        // Notes A, B, C hold 30, 20, 10. Dragging C to the top gives the order C, A, B.
        val newOrder = listOf("C", "A", "B")
        val values = OrderPlan.assign(listOf(30, 20, 10))
        val byNote = newOrder.zip(values).toMap()
        assertEquals(30L, byNote["C"])
        assertEquals(20L, byNote["A"])
        assertEquals(10L, byNote["B"])
    }
}
