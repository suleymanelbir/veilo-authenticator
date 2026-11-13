package com.selbir.authenticator.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class VeiloProductsUrlTest {
    // Installed versions of both editions open this address; changing it strands them.
    @Test
    fun productsPageAddressIsFixed() {
        assertEquals("https://auth.veilo.tr/#ecosystem", VEILO_PRODUCTS_URL)
    }
}
