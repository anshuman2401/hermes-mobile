package com.hermes.bot.feature.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectLayoutPolicyTest {
    @Test
    fun narrowPhoneStacksWhileOrdinaryPhoneKeepsTheSecondaryRow() {
        // Screen widths less the two 24dp content gutters.
        assertTrue(ConnectLayoutPolicy.stackSecondaryFields(320f - 48f, 1f))
        assertFalse(ConnectLayoutPolicy.stackSecondaryFields(360f - 48f, 1f))
        assertFalse(ConnectLayoutPolicy.stackSecondaryFields(390f - 48f, 1f))
    }

    @Test
    fun enlargedTextStacksEvenWhenTheWindowHasRoom() {
        assertFalse(ConnectLayoutPolicy.stackSecondaryFields(342f, 1.2f))
        assertTrue(ConnectLayoutPolicy.stackSecondaryFields(342f, 1.21f))
        assertTrue(ConnectLayoutPolicy.stackSecondaryFields(600f, 2f))
    }

    @Test
    fun widthBoundaryKeepsLabelsOutOfTheCrampedRow() {
        assertTrue(ConnectLayoutPolicy.stackSecondaryFields(299f, 1f))
        assertFalse(ConnectLayoutPolicy.stackSecondaryFields(300f, 1f))
    }
}
