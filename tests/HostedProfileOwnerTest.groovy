import org.moqui.util.MNode
import org.moqui.util.WebUtilities
import org.moqui.entity.EntityValue
import java.lang.reflect.Proxy
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.Authenticator
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class ProfileHttpClient extends HttpClient {
    ProfileFixture f
    @Override Optional<CookieHandler> cookieHandler(){Optional.empty()}
    @Override Optional<Duration> connectTimeout(){Optional.empty()}
    @Override HttpClient.Redirect followRedirects(){HttpClient.Redirect.NEVER}
    @Override Optional<ProxySelector> proxy(){Optional.empty()}
    @Override SSLContext sslContext(){throw new UnsupportedOperationException()}
    @Override SSLParameters sslParameters(){new SSLParameters()}
    @Override Optional<Authenticator> authenticator(){Optional.empty()}
    @Override HttpClient.Version version(){HttpClient.Version.HTTP_2}
    @Override Optional<Executor> executor(){Optional.empty()}
    @Override <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> ignored){
        assert request.method()=='POST'&&request.timeout().get()==Duration.ofSeconds(30)
        def bytes=new ByteArrayOutputStream();def done=new CompletableFuture<Void>()
        request.bodyPublisher().get().subscribe(new Flow.Subscriber<ByteBuffer>(){
            @Override void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE)}
            @Override void onNext(ByteBuffer data){byte[] next=new byte[data.remaining()];data.get(next);bytes.write(next)}
            @Override void onError(Throwable error){done.completeExceptionally(error)}
            @Override void onComplete(){done.complete(null)}
        })
        done.get(1,TimeUnit.SECONDS)
        def body=f.transport(request.uri().toString(),bytes.toString('UTF-8'))
        // Real Authorize.Net XML API responses begin with a UTF-8 byte-order mark.
        if(f.bom&&body!=null)body='\uFEFF'+body
        return [statusCode:{->f.httpStatus},body:{->body}] as HttpResponse<T>
    }
    @Override <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new UnsupportedOperationException()}
    @Override <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){throw new UnsupportedOperationException()}
}


// Real service XML/scripts, real Moqui XML action compiler. Only persistence and HTTP are isolated.
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
class ProfileFind {
    ProfileFixture f;String entity;Map keys=[:];boolean locked=false
    def condition(String key,Object value){keys[key]=value;this}
    def condition(Map values){
        def names = entity=='AuthorizeDotNet.PaymentGatewayAuthorizeNet' ? ['paymentGatewayConfigId'] : entity=='mantle.party.Party' ? ['partyId'] : []
        names.each{key->keys[key]=values[key]};this
    }
    def conditionDate(String from,String thru,Object at){keys._active=true;this}
    def forUpdate(boolean value){locked=value;this}
    def useCache(boolean ignored){this};def disableAuthz(){this};def orderBy(String ignored){this}
    List list(){
        if(locked){f.locks<<entity;f.events<<'lock:'+entity}
        (f.tables[entity]?:[]).findAll{row->keys.every{k,v->k=='_active' ? !row.thruDate : row[k]==v}}
    }
    def one(){def rows=list();rows ? f.value(rows[0]) : null}
}
class ProfileCall {
    ProfileFixture f;String service;Map input=[:]
    def name(String name){service=name;this};def parameters(Map data){input.putAll(data);this}
    def disableAuthz(){this}
    Map call(){
        if(service=='mantle.party.ContactServices.get#PartyContactInfo')return [emailAddress:'patient@example.invalid']
        if(service=='AuthorizeDotNet.CimServices.get#PaymentGatewayConfig')return [paymentGatewayConfigId:input.paymentGatewayConfigId?:'selected']
        if(service=='AuthorizeDotNet.CimPaymentServices.mask#CimResponseForLog')return [logRespString:'masked']
        assert service.startsWith('AuthorizeDotNet.HostedServices.'):'Unexpected internal service'
        f.run(service.tokenize('.').last().split('#')[0],service.tokenize('#').last(),input)
    }
}
class ProfileFixture {
    Map tables=[
        'mantle.party.Party':[[partyId:'patient',gatewayCimId:'123']],
        'mantle.party.PartyRole':[[partyId:'patient',roleTypeId:'Customer']],
        'mantle.party.contact.PartyContactMech':[[partyId:'patient',contactMechId:'email',contactMechPurposeId:'EmailPrimary']],
        'mantle.party.contact.ContactMech':[[contactMechId:'email',contactMechTypeEnumId:'CmtEmailAddress',infoString:'patient@example.invalid']],
        'AuthorizeDotNet.PaymentGatewayAuthorizeNet':[[paymentGatewayConfigId:'selected',login:'TESTlogin',tranKey:'TESTkey',transactionUrl:'https://example.invalid/selected']],
        'mantle.order.OrderHeader':[[orderId:'order',grandTotal:75G]],
        'mantle.order.OrderPart':[[orderId:'order',orderPartSeqId:'01',customerPartyId:'patient']]
    ]
    int httpStatus=200;boolean bom=true
    String caller='patient';List errors=[],requests=[],locks=[],events=[],logs=[];int writes=0
    Closure response;Map ec
    ProfileFixture(){
        ec=[user:[userId:'user',userAccount:[partyId:caller],nowTimestamp:new java.sql.Timestamp(1),getPreference:{String ignored->'selected'}],
            entity:[find:{String entity->new ProfileFind(f:this,entity:entity)}],
            service:[sync:{->new ProfileCall(f:this)}],
            message:[addError:{String text->errors<<text},hasError:{->!errors.empty}],
            logger:[log:{Object... args->logs.addAll(args)},warn:{Object... args->logs.addAll(args)},info:{Object... args->logs.addAll(args)}],
            resource:[expand:{String value,String ignored->value},script:{String location,String method,Map extra->
                assert location=='component://AuthorizeDotNet/service/AuthorizeDotNet/HostedHttp.groovy'
                def helper=new GroovyShell(this.class.classLoader).parse(new File(location.replace('component://AuthorizeDotNet/','')))
                helper.binding=new Binding([ec:ec]+extra);helper.invokeMethod(method,null)
            }]]
        WebUtilities.metaClass.'static'.simpleHttpStringRequest={String url,String body,String type->throw new AssertionError('Legacy trust-all HTTP helper was called')}
        HttpClient.metaClass.'static'.newBuilder={->
            def holder=[:]
            def builder=[connectTimeout:{Duration timeout->assert timeout==Duration.ofSeconds(15);holder.value},
                followRedirects:{HttpClient.Redirect redirect->assert redirect==HttpClient.Redirect.NEVER;holder.value},
                build:{->new ProfileHttpClient(f:this)}] as HttpClient.Builder
            holder.value=builder;builder
        }
    }
    String transport(String url,String body){
        def request=new groovy.xml.XmlParser().parseText(body)
        if(request.name().toString().endsWith('getCustomerProfileRequest')||request.name().toString().endsWith('createCustomerProfileRequest')||request.name().toString().endsWith('getHostedPaymentPageRequest'))assert locks.contains('mantle.party.Party'):'Profile/mint HTTP without Party lock'
        requests<<[url:url,body:body,node:request];events<<'http:'+request.name().toString()
        if(response)return response.call(request,url)
        return profile('patient','patient@example.invalid','123')
    }

    static String profile(String owner,String email,String id='123'){
        """<getCustomerProfileResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode><message><code>I00001</code><text>Successful.</text></message></messages><profile><merchantCustomerId>${owner}</merchantCustomerId><email>${email}</email><customerProfileId>${id}</customerProfileId><paymentProfiles><payment><creditCard><cardNumber>XXXX1111</cardNumber></creditCard></payment></paymentProfiles></profile></getCustomerProfileResponse>"""
    }
    static String created(String id='456'){"""<createCustomerProfileResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode><message><code>I00001</code><text>Successful.</text></message></messages><customerProfileId>${id}</customerProfileId></createCustomerProfileResponse>"""}
    static String failure(String code,String text){"""<createCustomerProfileResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Error</resultCode><message><code>${code}</code><text>${text}</text></message></messages></createCustomerProfileResponse>"""}
    EntityValue value(Map row){
        Proxy.newProxyInstance(EntityValue.classLoader,[EntityValue] as Class[],{proxy,method,args->
            if(method.name=='get')return row[args[0]]
            if(method.name=='set'||method.name=='put')return row.put(args[0],args[1])
            if(method.name=='update'){writes++;events<<'write';return proxy}
            if(method.name=='toString')return 'IsolatedEntity'
            if(method.name=='getEntityName')return 'IsolatedEntity'
            if(method.name=='getMap')return row
            if(method.name=='getString')return row[args[0]]?.toString()
            if(method.name=='equals')return proxy.is(args[0])
            if(method.name=='hashCode')return System.identityHashCode(proxy)
            row.getClass().getMethod(method.name,method.parameterTypes).invoke(row,args)
        } as java.lang.reflect.InvocationHandler) as EntityValue
    }
    Map run(String verb='ensure',String noun='CustomerProfile',Map input=[:]){
        ec.user.userAccount.partyId=caller
        def node=MNode.parse(new File('service/AuthorizeDotNet/HostedServices.xml')).children('service').find{it.attribute('verb')==verb&&it.attribute('noun')==noun}
        assert node:'Missing actual service definition'
        Map context=[ec:ec,partyId:'patient',paymentGatewayConfigId:'selected',customerProfileId:null,orderId:'order',orderPartSeqId:'01',iframeCommunicatorUrl:'https://example.invalid/bridge']+input
        if(node.attribute('type')=='script'){
            def script=new GroovyShell(this.class.classLoader).parse(new File(node.attribute('location').replace('component://AuthorizeDotNet/','')))
            script.binding=new Binding(context)
            return script.invokeMethod(node.attribute('method'),null)?:[:]
        }
        Closure seed;seed={MNode n->['value-field','out-map','field-name','field'].each{String attr->String key=n.attribute(attr);if(key&&!key.contains('.')&&!context.containsKey(key))context[key]=null};n.children((String)null).each{seed(it)}};seed(node)
        def script=this.class.classLoader.loadClass('XmlServiceHarness').compile('service/AuthorizeDotNet/HostedServices.xml',verb,noun,context)
        script.run();def result=script.getBinding().getVariables()
        node.first('out-parameters')?.children('parameter')?.collectEntries{[(it.attribute('name')):result[it.attribute('name')]]}?:[:]
    }
    Map ensure(){run()}
    Map mint(){run('mint','HostedPaymentToken')}
}
int passed=0;List failures=[]
def check={String name,Closure body->try{body();passed++;println 'PASS '+name}catch(Throwable error){failures<<name;println 'FAIL '+name+' ['+error.class.simpleName+': '+error.message+']'}}
check('existing same-owner profile is fetched under selected merchant and Party lock before reuse'){
    def f=new ProfileFixture();f.tables['AuthorizeDotNet.PaymentGatewayAuthorizeNet'][0].putAll([login:'TEST<&login',tranKey:'TEST<&key']);assert f.ensure().customerProfileId=='123';assert !f.errors&&f.writes==0
    assert f.requests.size()==1&&f.requests[0].url=='https://example.invalid/selected'
    def request=f.requests[0].node;assert request.name().toString().endsWith('getCustomerProfileRequest')
    assert request.customerProfileId.text()=='123'&&request.merchantAuthentication.name.text()=='TEST<&login'&&request.merchantAuthentication.transactionKey.text()=='TEST<&key'
    assert request.unmaskExpirationDate.text()!='true'&&request.includeIssuerInfo.text()!='true'
    assert f.events[0]=='lock:mantle.party.Party'
}
['patient','patient_ab_-Z'].each{owner->check('exact party or historical URL-safe random suffix accepted '+owner){
    def f=new ProfileFixture();f.response={request,url->ProfileFixture.profile(owner,'patient@example.invalid')};assert f.ensure().customerProfileId=='123'&&!f.errors
}}
['other','patient2_abcde','patient_abcdef','patient_abc!Z','patient_','prefixpatient_abcde'].each{owner->check('foreign or malformed merchant customer identity rejected '+owner){
    def f=new ProfileFixture();f.response={request,url->ProfileFixture.profile(owner,'patient@example.invalid')}
    assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&f.requests.size()==1
}}
['other@example.invalid',''].each{email->check('remote primary email mismatch is rejected '+(email?'different':'missing')){
    def f=new ProfileFixture();f.response={request,url->ProfileFixture.profile('patient',email)};assert !f.ensure().customerProfileId&&f.errors&&f.writes==0
}}
check('profile ID collision in another merchant cannot reach the hosted token mint'){
    def f=new ProfileFixture();f.response={request,url->ProfileFixture.profile('other','other@example.invalid')}
    assert !f.mint().formToken&&f.errors&&f.writes==0
    assert f.requests.size()==1&&f.requests.every{it.node.name().toString().endsWith('getCustomerProfileRequest')}
}
['wrong-response-id','missing-profile','wrong-root','duplicate-profile','error','malformed','doctype','transport-failure','timeout'].each{kind->check('invalid verification response is generic and never mutates '+kind){
    def f=new ProfileFixture();f.response={request,url->
        switch(kind){
            case 'wrong-response-id':return ProfileFixture.profile('patient','patient@example.invalid','999')
            case 'missing-profile':return '<getCustomerProfileResponse><messages><resultCode>Ok</resultCode></messages></getCustomerProfileResponse>'
            case 'wrong-root':return ProfileFixture.profile('patient','patient@example.invalid').replace('getCustomerProfileResponse','otherResponse')
            case 'duplicate-profile':return ProfileFixture.profile('patient','patient@example.invalid').replace('</profile>','</profile><profile><merchantCustomerId>patient</merchantCustomerId><email>patient@example.invalid</email><customerProfileId>123</customerProfileId></profile>')
            case 'error':return ProfileFixture.failure('E00040','Missing record 123')
            case 'malformed':return '<unclosed'
            case 'doctype':return '<!DOCTYPE x SYSTEM "https://example.invalid/SHOULD_NOT_FETCH">'+ProfileFixture.profile('patient','patient@example.invalid')
            case 'transport-failure':throw new IOException('Synthetic private transport body')
            case 'timeout':throw new java.net.http.HttpTimeoutException('Synthetic private timeout body')
        }
    };assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&f.logs.empty
}}
['caller','role','missing-email','ambiguous-email','wrong-contact-type','foreign-email'].each{kind->check('untrusted local patient or email evidence fails before transport '+kind){
    def f=new ProfileFixture()
    switch(kind){
        case 'caller':f.caller='other';break
        case 'role':f.tables['mantle.party.PartyRole']=[[partyId:'patient',roleTypeId:'Provider']];break
        case 'missing-email':f.tables['mantle.party.contact.PartyContactMech']=[];break
        case 'ambiguous-email':f.tables['mantle.party.contact.PartyContactMech']<<[partyId:'patient',contactMechId:'other',contactMechPurposeId:'EmailPrimary'];break
        case 'wrong-contact-type':f.tables['mantle.party.contact.ContactMech'][0].contactMechTypeEnumId='CmtPostalAddress';break
        case 'foreign-email':f.tables['mantle.party.contact.PartyContactMech'][0].partyId='other';break
    };assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&f.requests.empty
}}
[301,302,400,500].each{status->check('non-success HTTP status never proves a profile '+status){
    def f=new ProfileFixture();f.httpStatus=status
    assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&f.requests.size()==1
}}
check('dual-role patient remains eligible'){
    def f=new ProfileFixture();f.tables['mantle.party.PartyRole']<<[partyId:'patient',roleTypeId:'Provider'];assert f.ensure().customerProfileId=='123'
}
check('verified existing profile reaches hosted token only after owner verification'){
    def f=new ProfileFixture()
    f.response={request,url->request.name().toString().endsWith('getCustomerProfileRequest') ? ProfileFixture.profile('patient','patient@example.invalid') : '<getHostedPaymentPageResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><token>ISOLATED_TOKEN_SENTINEL</token></getHostedPaymentPageResponse>'}
    def result=f.mint()
    assert !f.errors&&result.formToken=='ISOLATED_TOKEN_SENTINEL'&&result.amount==75G&&f.writes==0
    assert f.requests.size()==2&&f.requests[0].node.name().toString().endsWith('getCustomerProfileRequest')
    assert f.requests[1].node.name().toString().endsWith('getHostedPaymentPageRequest')
    assert f.requests[1].node.transactionRequest.profile.customerProfileId.text()=='123'
    assert !f.tables.values().flatten().any{it.values().contains(result.formToken)}
}
check('new successful profile is bound to escaped creation input and stored only after successful response'){
    def f=new ProfileFixture();f.tables['mantle.party.Party'][0].gatewayCimId=null
    f.tables['mantle.party.contact.ContactMech'][0].infoString='person&tag@example.invalid'
    f.tables['AuthorizeDotNet.PaymentGatewayAuthorizeNet'][0].putAll([login:'TEST<&login',tranKey:'TEST<&key'])
    f.response={request,url->
        assert request.name().toString().endsWith('createCustomerProfileRequest')
        assert request.merchantAuthentication.name.text()=='TEST<&login'&&request.merchantAuthentication.transactionKey.text()=='TEST<&key'
        assert request.profile.merchantCustomerId.text()==~ /patient_[A-Za-z0-9_-]{5}/
        assert request.profile.email.text()=='person&tag@example.invalid';ProfileFixture.created()
    }
    assert f.ensure().customerProfileId=='456'&&!f.errors&&f.writes==1&&f.tables['mantle.party.Party'][0].gatewayCimId=='456'
    assert f.events.last()=='write'&&f.logs.empty
}
['E00003','E00027','E00040'].each{code->check('arbitrary error digits cannot become a profile '+code){
    def f=new ProfileFixture();f.tables['mantle.party.Party'][0].gatewayCimId=null
    f.response={request,url->ProfileFixture.failure(code,'Failure 12345 for account 67890')}
    assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&f.requests.size()==1&&!f.tables['mantle.party.Party'][0].gatewayCimId&&f.logs.empty
}}
check('duplicate E00039 is fetched and verified before binding recovered ID'){
    def f=new ProfileFixture();f.tables['mantle.party.Party'][0].gatewayCimId=null
    f.response={request,url->request.name().toString().endsWith('createCustomerProfileRequest') ? ProfileFixture.failure('E00039','A duplicate record with ID 555 already exists.') : ProfileFixture.profile('patient_ab_-Z','patient@example.invalid','555')}
    assert f.ensure().customerProfileId=='555'&&!f.errors&&f.writes==1&&f.requests.size()==2
    assert f.requests[1].node.customerProfileId.text()=='555'
}
check('duplicate E00039 for a different owner is never bound'){
    def f=new ProfileFixture();f.tables['mantle.party.Party'][0].gatewayCimId=null
    f.response={request,url->request.name().toString().endsWith('createCustomerProfileRequest') ? ProfileFixture.failure('E00039','A duplicate record with ID 555 already exists.') : ProfileFixture.profile('other','patient@example.invalid','555')}
    assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&!f.tables['mantle.party.Party'][0].gatewayCimId
}
['IDs 123 and 456','A duplicate record with ID 123 already exists. extra 456','A duplicate record already exists.'].each{text->check('malformed duplicate text is not interpreted as numeric identity '+text){
    def f=new ProfileFixture();f.tables['mantle.party.Party'][0].gatewayCimId=null;f.response={request,url->ProfileFixture.failure('E00039',text)}
    assert !f.ensure().customerProfileId&&f.errors&&f.writes==0&&f.requests.size()==1
}}
['bad','0','123 456'].each{id->check('malformed successful creation profile is not persisted '+id){
    def f=new ProfileFixture();f.tables['mantle.party.Party'][0].gatewayCimId=null;f.response={request,url->ProfileFixture.created(id)}
    assert !f.ensure().customerProfileId&&f.errors&&f.writes==0
}}
check('direct transaction confirmation uses selected merchant escaped XML and safe transport'){
    def f=new ProfileFixture();f.tables['AuthorizeDotNet.PaymentGatewayAuthorizeNet'][0].putAll([login:'TEST<&login',tranKey:'TEST<&key'])
    f.response={request,url->
        assert request.name().toString().endsWith('getTransactionDetailsRequest')
        assert request.merchantAuthentication.name.text()=='TEST<&login'&&request.merchantAuthentication.transactionKey.text()=='TEST<&key'
        assert request.transId.text()=='TRANSACTION<&SENTINEL'
        '<getTransactionDetailsResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><transaction><transactionStatus>declined</transactionStatus><responseCode>2</responseCode></transaction></getTransactionDetailsResponse>'
    }
    def result=f.run('confirm','HostedTransaction',[transId:'TRANSACTION<&SENTINEL'])
    assert !f.errors&&result.authorized==false&&f.writes==0&&f.requests.size()==1
}
check('unsettled settled-batch and transaction-list scans use safe transport before transaction details'){
    def f=new ProfileFixture();f.tables['AuthorizeDotNet.PaymentGatewayAuthorizeNet'][0].putAll([login:'TEST<&login',tranKey:'TEST<&key'])
    List operations=[]
    f.response={request,url->
        String operation=request.name().toString().replaceAll(/^.*\}/,'');operations<<operation
        assert request.merchantAuthentication.name.text()=='TEST<&login'&&request.merchantAuthentication.transactionKey.text()=='TEST<&key'
        switch(operation){
            case 'getUnsettledTransactionListRequest':return '<getUnsettledTransactionListResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><totalNumInResultSet>0</totalNumInResultSet><transactions/></getUnsettledTransactionListResponse>'
            case 'getSettledBatchListRequest':return '<getSettledBatchListResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><batchList><batch><batchId>BATCH&amp;SENTINEL</batchId></batch></batchList></getSettledBatchListResponse>'
            case 'getTransactionListRequest':
                assert request.batchId.text()=='BATCH&SENTINEL'
                return '<getTransactionListResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><totalNumInResultSet>1</totalNumInResultSet><transactions><transaction><invoiceNumber>order</invoiceNumber><transId>789</transId></transaction></transactions></getTransactionListResponse>'
            case 'getTransactionDetailsRequest':
                assert request.transId.text()=='789'
                return '<getTransactionDetailsResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><transaction><transactionStatus>declined</transactionStatus><responseCode>2</responseCode></transaction></getTransactionDetailsResponse>'
            default:throw new AssertionError('Unexpected scan operation')
        }
    }
    def result=f.run('confirm','HostedTransaction',[transId:null])
    assert !f.errors&&result.authorized==false&&f.writes==0
    assert operations==['getUnsettledTransactionListRequest','getSettledBatchListRequest','getTransactionListRequest','getTransactionDetailsRequest']
}
[302,500].each{status->check('hosted confirmation refuses HTTP '+status+' without following redirects or retrying'){
    def f=new ProfileFixture();f.httpStatus=status
    f.response={request,url->'<getTransactionDetailsResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><transaction><transactionStatus>declined</transactionStatus><responseCode>2</responseCode></transaction></getTransactionDetailsResponse>'}
    boolean rejected=false
    try{f.run('confirm','HostedTransaction',[transId:'789'])}catch(Exception error){rejected=error.message=='Payment gateway request unavailable'&&error.cause==null}
    assert rejected&&f.requests.size()==1&&f.writes==0
}}
[true,false].each{withBom->check('gateway response '+(withBom?'with':'without')+' a leading byte-order mark creates and binds a new profile'){
    // Authorize.Net prefixes XML API responses with U+FEFF; the strict parser must see clean XML.
    def f=new ProfileFixture();f.bom=withBom;f.tables['mantle.party.Party'][0].gatewayCimId=null
    f.response={request,url->ProfileFixture.created('456')}
    assert f.ensure().customerProfileId=='456'&&!f.errors&&f.writes==1
}}
check('hosted token mint parses a byte-order-mark response from the real gateway shape'){
    def f=new ProfileFixture()
    f.response={request,url->request.name().toString().endsWith('getCustomerProfileRequest') ? ProfileFixture.profile('patient','patient@example.invalid') : '<getHostedPaymentPageResponse xmlns="AnetApi/xml/v1/schema/AnetApiSchema.xsd"><messages><resultCode>Ok</resultCode></messages><token>ISOLATED_TOKEN_SENTINEL</token></getHostedPaymentPageResponse>'}
    assert f.mint().formToken=='ISOLATED_TOKEN_SENTINEL'&&!f.errors
}
['mint','confirm'].each{operation->check('malformed hosted '+operation+' response never exposes parser body diagnostics'){
    def f=new ProfileFixture();f.response={request,url->
        request.name().toString().endsWith('getCustomerProfileRequest') ? ProfileFixture.profile('patient','patient@example.invalid') : '<gatewayResponse>&PRIVATE_RESPONSE_SENTINEL;</gatewayResponse>'
    }
    boolean rejected=false
    try{operation=='mint' ? f.mint() : f.run('confirm','HostedTransaction',[transId:'789'])}
    catch(Exception error){rejected=error.message=='Invalid payment gateway response'&&error.cause==null}
    assert rejected&&f.writes==0&&f.logs.empty
}}
println "Hosted profile ownership: ${passed} passed, ${failures.size()} failed"
assert failures.empty:failures.join('; ')
