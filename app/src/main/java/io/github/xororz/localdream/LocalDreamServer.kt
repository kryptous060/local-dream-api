import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class LocalDreamServer(
    port: Int,
    private val onGenerateImage: (String, String) -> Unit,
    private val onUpdateSaveFolder: (String) -> Unit
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.POST) {
            try {
                session.parseBody(HashMap<String, String>())
                
                // Read the JSON body from the request
                val contentLength = session.headers["content-length"]?.toInt() ?: 0
                val buffer = ByteArray(contentLength)
                session.inputStream.read(buffer, 0, contentLength)
                val jsonBody = String(buffer)

                return when (uri) {
                    "/generate" -> handleGenerate(jsonBody)
                    "/config" -> handleConfig(jsonBody)
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Endpoint not found")
                }
            } catch (e: Exception) {
                Log.e("LocalDreamServer", "Error parsing request", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }

        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only POST supported")
    }

    private fun handleGenerate(jsonBody: String): Response {
        val request = gson.fromJson(jsonBody, GenerateRequest::class.java)
        
        // Trigger the callback to the main app logic
        onGenerateImage(request.positive_prompt ?: "", request.negative_prompt ?: "")
        
        return newFixedLengthResponse(
            Response.Status.OK, 
            "application/json", 
            "{\"status\":\"success\", \"message\":\"Generation started\"}"
        )
    }

    private fun handleConfig(jsonBody: String): Response {
        val request = gson.fromJson(jsonBody, ConfigRequest::class.java)
        
        request.save_folder?.let { 
            onUpdateSaveFolder(it) 
        }
        
        return newFixedLengthResponse(
            Response.Status.OK, 
            "application/json", 
            "{\"status\":\"success\", \"message\":\"Configuration updated\"}"
        )
    }
}
// Data classes for JSON parsing
data class GenerateRequest(val positive_prompt: String?, val negative_prompt: String?)
data class ConfigRequest(val save_folder: String?)
