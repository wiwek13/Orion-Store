package com.orion.store.morphe

import com.orion.store.morphe.store.MetaMorpheScraper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaMorpheScraperTest {

    @Test
    fun `test scrape sources from metamorphe`() = runBlocking {
        val sources = MetaMorpheScraper.scrapeSources()
        println("Scraped sources count: ${sources.size}")
        sources.forEach { println(" - ${it.name} (${it.count} entries) -> ${it.url}") }

        assertTrue("Should scrape at least 3 community sources from https://metamorphe.vercel.app/#sources", sources.size >= 3)
        assertTrue("Should include Awesome Morphe or Morphe Community", sources.any { it.name.contains("Morphe", true) })
    }

    @Test
    fun `test scrape registry bundles from metamorphe`() = runBlocking {
        val regBundles = MetaMorpheScraper.scrapeRegistryBundles()
        println("Scraped registry bundles count: ${regBundles.size}")
        assertTrue("Should scrape registry bundles from news.js", regBundles.isNotEmpty())
        assertNotNull(regBundles.first().id)
    }
}
