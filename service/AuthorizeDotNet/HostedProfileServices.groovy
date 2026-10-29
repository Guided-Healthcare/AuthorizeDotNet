import groovy.namespace.QName
import groovy.xml.MarkupBuilder
import groovy.xml.XmlParser
import org.moqui.util.StringUtilities
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

// HTTP responses may include masked payment data. Keep them in local variables only:
// never return them, include them in error messages, or log request/response/exception bodies.
private Map failure() {
    ec.message.addError('Could not verify the payment profile. Contact support before trying again.')
    return [:]
}
private def find(String entity, Map keys) {
    def query = ec.entity.find(entity).useCache(false).disableAuthz()
    keys.each { key, value -> query.condition(key, value) }
    return query
}
private boolean profileId(Object value) { value instanceof String && value ==~ /[1-9][0-9]*/ }

/** The authenticated patient and selected merchant are server inputs, never caller overrides.
 * The Party lock joins ensure/mint's transaction and remains held through the hosted-page call. */
private Map ownerContext() {
    String pid = partyId as String
    if (!ec.user.userId || !pid || ec.user.userAccount?.partyId != pid) return [:]
    def party = find('mantle.party.Party', [partyId:pid]).forUpdate(true).one()
    if (!party || !find('mantle.party.PartyRole', [partyId:pid,roleTypeId:'Customer']).one()) return [:]
    def gateway = find('AuthorizeDotNet.PaymentGatewayAuthorizeNet', [paymentGatewayConfigId:paymentGatewayConfigId]).one()
    if (!gateway?.login || !gateway.tranKey || !gateway.transactionUrl) return [:]
    def target = new URI(gateway.transactionUrl as String)
    if (target.scheme != 'https' || !target.host || target.userInfo || target.fragment) return [:]

    // No fallback to user-supplied email, a different purpose, or another party's contact.
    def contacts = find('mantle.party.contact.PartyContactMech', [partyId:pid,contactMechPurposeId:'EmailPrimary'])
            .conditionDate('fromDate','thruDate',ec.user.nowTimestamp).list()
    if (contacts.size() != 1 || !contacts[0].contactMechId) return [:]
    def contact = find('mantle.party.contact.ContactMech', [contactMechId:contacts[0].contactMechId]).one()
    String email = (contact?.infoString as String)?.trim()
    if (contact?.contactMechTypeEnumId != 'CmtEmailAddress' || !email || email.length() > 255 ||
            !email.contains('@') || (email =~ /[\p{Cntrl}]/).find()) return [:]
    return [party:party,gateway:gateway,partyId:pid,email:email]
}

private String requestXml(Map owner, String operation, String candidate = null, String merchantId = null) {
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    xml."${operation}"(xmlns:'AnetApi/xml/v1/schema/AnetApiSchema.xsd') {
        merchantAuthentication { name(owner.gateway.login); transactionKey(owner.gateway.tranKey) }
        if (candidate) {
            customerProfileId(candidate)
            unmaskExpirationDate(false)
            includeIssuerInfo(false)
        } else {
            profile { merchantCustomerId(merchantId); email(owner.email) }
        }
    }
    return writer.toString()
}

private String post(Map owner, String xml) {
    return ec.resource.script('component://AuthorizeDotNet/service/AuthorizeDotNet/HostedHttp.groovy',
            'postHostedXml', [targetUrl:owner.gateway.transactionUrl,xmlPayload:xml]) as String
}

private def response(String body, String expectedRoot) {
    // Explicitly reject DOCTYPE/external entities. Suppress parser diagnostics that could
    // echo a gateway response; the caller receives only the generic service error above.
    def parser = new XmlParser(false, true, false)
    parser.setErrorHandler(new DefaultHandler() {
        @Override void error(SAXParseException ignored) { throw new SAXException('Invalid gateway response') }
        @Override void fatalError(SAXParseException ignored) { throw new SAXException('Invalid gateway response') }
    })
    def node = parser.parseText(body)
    def root = node.name()
    if (!(root instanceof QName) || root.localPart != expectedRoot ||
            root.namespaceURI != 'AnetApi/xml/v1/schema/AnetApiSchema.xsd' ||
            node.messages.size() != 1 || node.messages.resultCode.size() != 1 ||
            node.messages.message.size() != 1 || node.messages.message.code.size() != 1) return null
    return node
}

Map verifyCustomerProfileOwner() {
    try {
        Map owner = ownerContext()
        String candidate = customerProfileId as String
        if (!owner || !profileId(candidate)) return failure()
        String body = post(owner, requestXml(owner, 'getCustomerProfileRequest', candidate))
        def node = response(body, 'getCustomerProfileResponse')
        if (!node || node.messages.resultCode.text() != 'Ok' || node.messages.message.code.text() != 'I00001' ||
                node.profile.size() != 1) return failure()
        def profile = node.profile[0]
        if (profile.customerProfileId.size() != 1 || profile.customerProfileId.text() != candidate ||
                profile.merchantCustomerId.size() != 1 || profile.email.size() != 1) return failure()
        String merchantId = profile.merchantCustomerId.text()
        String prefix = owner.partyId + '_'
        boolean ownedId = merchantId == owner.partyId || (merchantId.startsWith(prefix) &&
                merchantId.substring(prefix.length()) ==~ /[A-Za-z0-9_-]{5}/)
        if (!ownedId || !owner.email.equalsIgnoreCase(profile.email.text().trim())) return failure()
        return [customerProfileId:candidate]
    } catch (Exception ignored) {
        return failure()
    }
}

Map ensureCustomerProfile() {
    try {
        Map owner = ownerContext()
        if (!owner) return failure()
        String candidate = owner.party.gatewayCimId as String
        if (candidate) {
            // A numeric ID is scoped to a merchant. It is not ownership evidence on its own.
            return ec.service.sync().name('AuthorizeDotNet.HostedServices.verify#CustomerProfileOwner')
                    .parameters([partyId:owner.partyId,paymentGatewayConfigId:paymentGatewayConfigId,
                                 customerProfileId:candidate]).call() ?: [:]
        }
        String merchantId = owner.partyId + '_' + StringUtilities.getRandomString(5)
        if (merchantId.length() > 20) return failure() // Authorize.Net's merchantCustomerId limit; never truncate identity.
        String body = post(owner, requestXml(owner, 'createCustomerProfileRequest', null, merchantId))
        def node = response(body, 'createCustomerProfileResponse')
        if (!node) return failure()
        if (node.messages.resultCode.text() == 'Ok' && node.messages.message.code.text() == 'I00001') {
            if (node.customerProfileId.size() != 1 || !profileId(node.customerProfileId.text())) return failure()
            candidate = node.customerProfileId.text() // Successful creation is bound to our exact request.
        } else {
            if (node.messages.resultCode.text() != 'Error' || node.messages.message.code.text() != 'E00039' ||
                    node.messages.message.text.size() != 1) return failure()
            // Only the documented duplicate shape supplies a candidate. Error digits never prove identity.
            def duplicate = node.messages.message.text.text() =~ /^A duplicate record with ID ([1-9][0-9]*) already exists\.$/
            if (!duplicate.matches()) return failure()
            String recovered = duplicate.group(1)
            Map verified = ec.service.sync().name('AuthorizeDotNet.HostedServices.verify#CustomerProfileOwner')
                    .parameters([partyId:owner.partyId,paymentGatewayConfigId:paymentGatewayConfigId,
                                 customerProfileId:recovered]).call() ?: [:]
            if (ec.message.hasError() || verified.customerProfileId != recovered) return [:]
            candidate = recovered
        }
        owner.party.gatewayCimId = candidate
        owner.party.update()
        return [customerProfileId:candidate]
    } catch (Exception ignored) {
        return failure()
    }
}
