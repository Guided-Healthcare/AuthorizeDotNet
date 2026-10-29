import org.moqui.util.MNode
import org.moqui.util.WebUtilities
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityConditionFactoryImpl

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

class InstrumentHttpClient extends HttpClient {
    InstrumentFixture f
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
        return [statusCode:{->f.httpStatus},body:{->body}] as HttpResponse<T>
    }
    @Override <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new UnsupportedOperationException()}
    @Override <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){throw new UnsupportedOperationException()}
}



// Compile actual production XML actions. Replace only storage and HTTP; never call a gateway.
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
class InstrumentFind {
    InstrumentFixture f; String entity; Map keys=[:]; List conditions=[]; boolean locked=false
    def condition(String key,Object value){keys[key]=value;this}
    def condition(Map context){
        def primaryKeys=[
            'AuthorizeDotNet.PaymentGatewayAuthorizeNet':['paymentGatewayConfigId'],
            'mantle.order.OrderHeader':['orderId'], 'mantle.order.OrderPart':['orderId','orderPartSeqId'],
            'mantle.account.payment.Payment':['paymentId'], 'mantle.account.method.PaymentMethod':['paymentMethodId']]
        (primaryKeys[entity]?:[]).each{keys[it]=context[it]};this
    }
    def condition(EntityCondition condition){if(condition)conditions<<condition;this}
    def forUpdate(boolean value){locked=value;this}
    def useCache(boolean ignored){this}; def orderBy(String ignored){this}
    List list(){
        if(locked)f.events<<'lock:'+entity
        (f.tables[entity]?:[]).findAll{row->keys.every{k,v->row[k]==v}&&conditions.every{it.mapMatches(row)}}
    }
    def one(){def rows=list();this.class.classLoader.loadClass('XmlServiceHarness').value(rows?rows[0]:null)}
}
class InstrumentCall {
    InstrumentFixture f; String service; Map input=[:]
    def name(String value){service=value;this}; def parameters(Map value){input.putAll(value);this}
    Map call(){f.call(service,input)}
}
class InstrumentFixture {
    static final String HOSTED='service/AuthorizeDotNet/HostedServices.xml'
    static final String MANTLE=System.getenv('MANTLE_USL_ROOT')+'/service/mantle/account/PaymentServices.xml'
    Map tables=[
        'mantle.order.OrderHeader':[[orderId:'synthetic-order',grandTotal:75G,currencyUomId:'USD',productStoreId:'synthetic-store']],
        'mantle.order.OrderPart':[[orderId:'synthetic-order',orderPartSeqId:'01',customerPartyId:'synthetic-customer']],
        'mantle.product.store.ProductStore':[[productStoreId:'synthetic-store',organizationPartyId:'synthetic-vendor']],
        'AuthorizeDotNet.PaymentGatewayAuthorizeNet':[[paymentGatewayConfigId:'synthetic-gateway',login:'SYNTHETIC',tranKey:'SYNTHETIC',transactionUrl:'https://example.invalid/never-contact']],
        'mantle.account.payment.Payment':[], 'mantle.account.method.PaymentGatewayResponse':[],
        'mantle.account.method.PaymentMethod':[[paymentMethodId:'synthetic-method',paymentMethodTypeEnumId:'PmtCreditCard',paymentGatewayConfigId:'synthetic-gateway']],
        'moqui.basic.Enumeration':[[enumId:'PiCreditCard',relatedEnumId:'PmtCreditCard']]
    ]
    List calls=[], events=[], errors=[]
    String txnStatus='capturedPendingSettlement', invoice='synthetic-order', amount='75.00', reason='1'
    Map ec
    InstrumentFixture(){
        def factory=new EntityConditionFactoryImpl(null)
        ec=[user:[nowTimestamp:new java.sql.Timestamp(1),getPreference:{String ignored->'synthetic-gateway'}],
            entity:[find:{String entity->new InstrumentFind(f:this,entity:entity)},conditionFactory:[
                makeActionConditionDirect:{String field,String operator,Object from,String value,String to,boolean ignoreCase,boolean ignoreIfEmpty,boolean orNull,String ignore->
                    assert !to&&!ignoreCase&&!orNull&&ignore=='false'
                    if(ignoreIfEmpty&&!from&&!value)return null
                    factory.makeCondition(field,factory.getComparisonOperator(operator),value?:from)
                }]],
            service:[sync:{->new InstrumentCall(f:this)}],
            message:[hasError:{->!errors.empty},addError:{String message->errors<<message}],
            logger:[log:{Object... ignored->},warn:{Object... ignored->}],
            resource:[expand:{String value,String ignored->value}]]
        ec.resource.script={String location,String method,Map extra->
            assert location=='component://AuthorizeDotNet/service/AuthorizeDotNet/HostedHttp.groovy'
            def helper=new GroovyShell(this.class.classLoader).parse(new File(location.replace('component://AuthorizeDotNet/','')))
            helper.binding=new Binding([ec:ec]+extra);helper.invokeMethod(method,null)
        }
        WebUtilities.metaClass.'static'.simpleHttpStringRequest={String url,String body,String type->throw new AssertionError('Legacy trust-all HTTP helper was called')}
        HttpClient.metaClass.'static'.newBuilder={->
            def holder=[:]
            def builder=[connectTimeout:{Duration timeout->assert timeout==Duration.ofSeconds(15);holder.value},
                followRedirects:{HttpClient.Redirect redirect->assert redirect==HttpClient.Redirect.NEVER;holder.value},
                build:{->new InstrumentHttpClient(f:this)}] as HttpClient.Builder
            holder.value=builder;builder
        }
    }
    int httpStatus=200
    String transport(String url,String body){
            assert url=='https://example.invalid/never-contact'
            def request=new groovy.xml.XmlParser().parseText(body)
            assert request.name().toString().endsWith('getTransactionDetailsRequest')
            assert request.transId.text()=='synthetic-transaction'
            events<<'isolated-details'
            """<getTransactionDetailsResponse><messages><resultCode>Ok</resultCode></messages><transaction><transactionStatus>${txnStatus}</transactionStatus><responseCode>${reason}</responseCode><order><invoiceNumber>${invoice}</invoiceNumber></order><settleAmount>${amount}</settleAmount></transaction></getTransactionDetailsResponse>"""
    }
    Map run(String file,String verb,String noun,Map input){
        def node=MNode.parse(new File(file)).children('service').find{it.attribute('verb')==verb&&it.attribute('noun')==noun}
        Map context=[ec:ec,paymentInstrumentEnumId:null,paymentMethodId:null,toPaymentMethodId:null,finAccountId:null,
                     paymentMethod:null,toPaymentMethod:null,statusId:null,orderId:null,orderPartSeqId:null,
                     paymentId:null,amount:null,amountUomId:null,paymentGatewayConfigId:null]+input
        Closure seed; seed={MNode n->['value-field','out-map','field-name','field','list'].each{attr->
            String key=n.attribute(attr);if(key&&key!='context'&&!key.contains('.')&&!context.containsKey(key))context[key]=null
        };n.children((String)null).each{seed(it)}};seed(node)
        def script=this.class.classLoader.loadClass('XmlServiceHarness').compile(file,verb,noun,context)
        script.run()
        Map result=script.getBinding().getVariables()
        // set#PaymentAutoInfo has auto-parameters; expose its resulting payment fields to create#Payment.
        result.findAll{k,v->k!='ec'&&k!='context'&&k!='result'}
    }
    Map confirm(){run(HOSTED,'confirm','HostedTransaction',[orderId:'synthetic-order',orderPartSeqId:'01',transId:'synthetic-transaction',paymentGatewayConfigId:'synthetic-gateway'])}
    Map call(String service,Map input){
        calls<<[service:service,input:new LinkedHashMap(input)];events<<service
        switch(service){
            case 'AuthorizeDotNet.CimServices.get#PaymentGatewayConfig':return [paymentGatewayConfigId:input.paymentGatewayConfigId]
            case 'mantle.account.PaymentServices.create#Payment':return run(MANTLE,'create','Payment',input)
            case 'mantle.account.PaymentServices.set#PaymentAutoInfo':return run(MANTLE,'set','PaymentAutoInfo',input)
            case 'create#mantle.account.payment.Payment':
                assert events.contains('lock:mantle.order.OrderHeader')
                Map row=input.findAll{k,v->k in ['orderId','orderPartSeqId','paymentTypeEnumId','statusId','paymentInstrumentEnumId','paymentMethodId','amount','amountUomId','fromPartyId','toPartyId','paymentRefNum','effectiveDate','paymentGatewayConfigId']}
                row.paymentId='synthetic-payment';tables['mantle.account.payment.Payment']<<row
                return [paymentId:row.paymentId]
            case 'update#mantle.account.payment.Payment':
                tables['mantle.account.payment.Payment'].find{it.paymentId==input.paymentId}.putAll(input);return [:]
            case 'create#mantle.account.method.PaymentGatewayResponse':
                tables['mantle.account.method.PaymentGatewayResponse']<<new LinkedHashMap(input);return [:]
            default:throw new AssertionError('Unexpected service boundary: '+service)
        }
    }
    Map payment(){tables['mantle.account.payment.Payment'][0]}
}
int passed=0;List failures=[]
def check={String name,Closure test->try{test();passed++;println 'PASS '+name}catch(Throwable error){failures<<name;println 'FAIL '+name+' ['+error.class.simpleName+': '+error.message+']'}}
// Catches an upstream-default assumption: selected gateway alone does not imply credit card.
check('characterization: actual Mantle auto-info defaults missing method and instrument to company check'){
    def f=new InstrumentFixture()
    def result=f.run(InstrumentFixture.MANTLE,'set','PaymentAutoInfo',[fromPartyId:'synthetic-customer',toPartyId:'synthetic-vendor',amount:75G,amountUomId:'USD',paymentGatewayConfigId:'synthetic-gateway'])
    assert result.paymentInstrumentEnumId=='PiCompanyCheck'
}
// Controls establish both legitimate ways the hosted caller can prevent that fallback.
check('actual Mantle auto-info preserves an explicit credit-card instrument'){
    def f=new InstrumentFixture()
    assert f.run(InstrumentFixture.MANTLE,'set','PaymentAutoInfo',[fromPartyId:'synthetic-customer',paymentInstrumentEnumId:'PiCreditCard',amountUomId:'USD']).paymentInstrumentEnumId=='PiCreditCard'
}
check('actual Mantle auto-info derives credit-card instrument from a bound method'){
    def f=new InstrumentFixture()
    assert f.run(InstrumentFixture.MANTLE,'set','PaymentAutoInfo',[fromPartyId:'synthetic-customer',paymentMethodId:'synthetic-method',amountUomId:'USD']).paymentInstrumentEnumId=='PiCreditCard'
}
// RED on GH119: a missing hosted input becomes the wrong stored instrument through real Mantle XML.
['capturedPendingSettlement','settledSuccessfully'].each{status->check('GH119: new hosted '+status+' payment is recorded as credit card'){
    def f=new InstrumentFixture();f.txnStatus=status
    def result=f.confirm()
    assert f.errors.empty&&result.authorized&&result.paymentStatusId=='PmntDelivered'
    assert f.payment().paymentInstrumentEnumId=='PiCreditCard': 'GH119: hosted credit-card transaction was recorded with '+f.payment().paymentInstrumentEnumId
}}
check('hosted confirmation retains exact order, amount, currency and transaction binding'){
    def f=new InstrumentFixture();f.confirm();def p=f.payment()
    assert [p.orderId,p.orderPartSeqId,p.amount,p.amountUomId,p.paymentRefNum,p.paymentGatewayConfigId,p.fromPartyId,p.toPartyId]==['synthetic-order','01',75G,'USD','synthetic-transaction','synthetic-gateway','synthetic-customer','synthetic-vendor']
}
check('retry reuses accepted payment without rewriting its historical instrument or duplicating response'){
    def f=new InstrumentFixture();f.confirm();Map before=new LinkedHashMap(f.payment());f.calls.clear();f.confirm()
    assert f.tables['mantle.account.payment.Payment'].size()==1&&f.payment()==before
    assert f.tables['mantle.account.method.PaymentGatewayResponse'].size()==1
    assert !f.calls.any{it.service in ['mantle.account.PaymentServices.create#Payment','update#mantle.account.payment.Payment']}
}
['PmntAuthorized','PmntDelivered','PmntConfirmed'].each{status->check('pre-existing '+status+' payment retains historical financial fields'){
    def f=new InstrumentFixture()
    Map historical=[paymentId:'historical-synthetic-payment',orderId:'synthetic-order',orderPartSeqId:'01',
        statusId:status,paymentInstrumentEnumId:'PiCompanyCheck',paymentMethodId:null,
        amount:75G,amountUomId:'USD',paymentRefNum:'historical-synthetic-reference',
        paymentGatewayConfigId:'historical-synthetic-gateway',effectiveDate:new java.sql.Timestamp(0)]
    f.tables['mantle.account.payment.Payment']<<new LinkedHashMap(historical)
    def result=f.confirm()
    assert result.paymentId=='historical-synthetic-payment'&&result.paymentStatusId==status
    assert f.payment()==historical&&f.tables['mantle.account.payment.Payment'].size()==1
    assert !f.calls.any{it.service in ['mantle.account.PaymentServices.create#Payment','update#mantle.account.payment.Payment']}
}}
['declined','wrong-invoice','wrong-amount'].each{kind->check('no payment write for '+kind){
    def f=new InstrumentFixture()
    if(kind=='declined'){f.txnStatus='declined';f.reason='2'}
    if(kind=='wrong-invoice')f.invoice='other-synthetic-order'
    if(kind=='wrong-amount')f.amount='76.00'
    f.confirm()
    assert f.tables['mantle.account.payment.Payment'].empty&&f.tables['mantle.account.method.PaymentGatewayResponse'].empty
}}
println "Hosted payment instrument: ${passed} passed, ${failures.size()} failed"
assert failures.empty:failures.join('; ')
