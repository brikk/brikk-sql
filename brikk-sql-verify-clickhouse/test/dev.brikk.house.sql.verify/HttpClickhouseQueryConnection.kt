package dev.brikk.house.sql.verify

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration

/** ClickHouse's own HTTP interface, sending nothing but the query: for profiles a JDBC driver's own settings break. */
internal class HttpClickhouseQueryConnection(private val base: String, private val user: String) : ClickhouseQueryConnection {
    private val client = HttpClient.newHttpClient()

    override fun queryString(sql: String, timeoutSeconds: Int): String? {
        val request = HttpRequest.newBuilder(URI.create("$base/?user=${URLEncoder.encode(user, Charsets.UTF_8)}&default_format=TSVRaw"))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw SQLException(response.body())
        return response.body().removeSuffix("\n")
    }

    override fun cancel() = Unit

    override fun close() = client.close()
}
