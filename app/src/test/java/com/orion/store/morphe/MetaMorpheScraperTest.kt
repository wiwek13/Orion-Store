package com.orion.store.morphe

import com.orion.store.morphe.store.MetaMorpheScraper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun `test parseBundlesJson supports repo keyed entries and multiple bundles per repo`() {
        val sampleJson = """
            [
              {
                "repo": "SysAdminDoc/HushGram",
                "name": "HushGram Stable",
                "repoDescription": "Instagram ad-free patches",
                "version": "1.2.3",
                "isPrerelease": false,
                "patches": [
                  {
                    "name": "Disable Ads",
                    "description": "Hides sponsored posts",
                    "category": "Ads",
                    "compatiblePackages": [
                      { "packageName": "com.instagram.android", "versions": ["350.0.0.1"] }
                    ]
                  }
                ]
              },
              {
                "repo": "SysAdminDoc/HushGram",
                "name": "HushGram Experimental",
                "repoDescription": "Instagram experimental patches",
                "version": "1.3.0-dev",
                "isPrerelease": false,
                "patches": []
              }
            ]
        """.trimIndent()
        val parsed = MetaMorpheScraper.parseBundlesJson(sampleJson)
        assertEquals(2, parsed.size)
        assertEquals("SysAdminDoc/HushGram", parsed[0].id)
        assertEquals("Instagram ad-free patches", parsed[0].description)
        assertEquals("HushGram Experimental", parsed[1].name)
    }

    @Test
    fun `test MppBundleResolver parses GitHub release mpp assets and repo slugs`() {
        assertEquals("SysAdminDoc/HushGram", com.orion.store.morphe.engine.MppBundleResolver.normalizeRepoSlug("https://github.com/SysAdminDoc/HushGram/releases"))
        assertEquals("MorpheApp/morphe-patches", com.orion.store.morphe.engine.MppBundleResolver.normalizeRepoSlug("MorpheApp/morphe-patches"))
        assertTrue(com.orion.store.morphe.engine.MppBundleResolver.isDirectBundleUrl("https://example.com/patches-1.0.mpp?dl=1"))

        val releaseObj = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "tag_name": "v0.0.20",
              "assets": [
                { "name": "patches-0.0.20.mpp.asc", "browser_download_url": "https://example.com/a.asc" },
                { "name": "patches-0.0.20.mpp", "browser_download_url": "https://github.com/SysAdminDoc/HushGram/releases/download/v0.0.20/patches-0.0.20.mpp" }
              ]
            }
            """.trimIndent()
        ) as kotlinx.serialization.json.JsonObject

        val asset = com.orion.store.morphe.engine.MppBundleResolver.findMppAssetInReleaseJson(releaseObj)
        assertNotNull(asset)
        assertEquals("patches-0.0.20.mpp", asset!!.first)
    }
}
