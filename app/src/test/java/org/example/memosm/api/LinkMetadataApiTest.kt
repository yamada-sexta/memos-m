package org.example.memosm.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LinkMetadataApiTest {
    @Test
    fun `metadata request preserves server path and encodes the complete link`() = runBlocking {
        val url = "https://example.com/page?one=a&two=b#section"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/memos/api/v1/memos/-/linkMetadata", chain.request().url.encodedPath)
            assertEquals(url, chain.request().url.queryParameter("url"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"url":"$url","title":"Example","description":"A page","image":"https://example.com/image.jpg"}""".toResponseBody())
                .build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://memos.example/memos/").client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonProvider.gson)).build()
        val api = MemosApiV0300Impl(retrofit.create(MemosApiV0300::class.java))
        val metadata = api.getLinkMetadata(url)
        assertEquals(url, metadata.url)
        assertEquals("Example", metadata.title)
        assertEquals("A page", metadata.description)
        assertEquals("https://example.com/image.jpg", metadata.image)
    }
}
