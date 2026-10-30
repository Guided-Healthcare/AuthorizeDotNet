import groovy.transform.Field
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

// Resource scripts are cached by Moqui. Reuse one thread-safe client, with the JDK's
// normal certificate and hostname validation. No custom SSL context/trust manager.
@Field static final HttpClient HOSTED_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()

/** Called through ResourceFacade.script(..., additionalContext), never a service call.
 * Credential-bearing XML is not a Moqui service parameter/result or diagnostic value. */
String postHostedXml() {
    try {
        URI target = new URI(targetUrl as String)
        if (target.scheme != 'https' || !target.host || target.userInfo || target.fragment || !(xmlPayload instanceof CharSequence))
            throw new IllegalArgumentException('Invalid gateway destination')
        def request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                .header('Content-Type','text/xml; charset=UTF-8')
                .POST(HttpRequest.BodyPublishers.ofString(xmlPayload as String, StandardCharsets.UTF_8)).build()
        def reply = HOSTED_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (reply.statusCode() != 200) throw new IOException('Gateway request unavailable')
        // Authorize.Net XML API responses begin with a UTF-8 byte-order mark, which the strict
        // XML parser rejects. Strip it, as Moqui's WebUtilities (StringUtilities.toStringCleanBom)
        // did for the previous transport.
        String body = reply.body()
        return body != null && body.startsWith('\uFEFF') ? body.substring(1) : body
    } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt()
        throw new IllegalStateException('Payment gateway request unavailable')
    } catch (Exception ignored) {
        // Do not retain a cause, URL, credentials, request, response, or transport exception body.
        throw new IllegalStateException('Payment gateway request unavailable')
    }
}

/** Parse locally without XML external entities or diagnostics containing response text. */
def parseHostedXml() {
    try {
        def parser = new groovy.xml.XmlParser(false, true, false)
        parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override void error(org.xml.sax.SAXParseException ignored) { throw new org.xml.sax.SAXException('Invalid gateway response') }
            @Override void fatalError(org.xml.sax.SAXParseException ignored) { throw new org.xml.sax.SAXException('Invalid gateway response') }
        })
        return parser.parseText(xmlPayload as String)
    } catch (Exception ignored) {
        throw new IllegalStateException('Invalid payment gateway response')
    }
}
