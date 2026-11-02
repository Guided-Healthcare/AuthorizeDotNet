import org.moqui.util.MNode
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
this.class.classLoader.parseClass(new File('tests/HostedPaymentInstrumentTest.groovy'))
int passed=0;def failures=[]
def check={String name,Closure body->try{body();passed++;println 'PASS '+name}catch(Throwable e){failures<<name;println 'FAIL '+name+' ['+e.class.simpleName+': '+e.message+']'}}
def fixture={String scenario->
 def f=this.class.classLoader.loadClass('InstrumentFixture').getDeclaredConstructor().newInstance()
 f.metaClass.lookupRequests=[];f.metaClass.lookupLogs=[]
 f.ec.logger.log={Object... args-> f.lookupLogs<<args[1].toString()}
 f.metaClass.transport={String url,String body->
  assert url=='https://example.invalid/never-contact'
  def req=new groovy.xml.XmlParser().parseText(body);String op=req.name().toString();assert ['getUnsettledTransactionListRequest','getSettledBatchListRequest','getTransactionListRequest','getTransactionDetailsRequest'].any{op.endsWith(it)};f.lookupRequests<<[op:op,offset:req.paging.offset.text()]
  if(scenario=='http-error')throw new IOException('synthetic transport failure')
  if(scenario=='malformed')return '<broken'
  if(scenario=='gateway-error')return '<response><messages><resultCode>Error</resultCode></messages></response>'
  if(op.endsWith('getTransactionDetailsRequest'))return "<response><messages><resultCode>Ok</resultCode></messages><transaction><transactionStatus>settledSuccessfully</transactionStatus><responseCode>1</responseCode><order><invoiceNumber>${scenario=='foreign'?'foreign-order':'synthetic-order'}</invoiceNumber></order><settleAmount>75.00</settleAmount></transaction></response>"
  if(op.endsWith('getSettledBatchListRequest'))return '<response><messages><resultCode>Ok</resultCode></messages><batchList><batch><batchId>fixture-batch</batchId></batch></batchList></response>'
  boolean match=scenario in ['found','foreign'] || (scenario=='settled'&&op.endsWith('getTransactionListRequest')) || (scenario=='page-two'&&req.paging.offset.text()=='1001')
  def rows=match?'<transaction><transId>123456789</transId><invoiceNumber>synthetic-order</invoiceNumber></transaction>':''
  if(scenario=='cap'||(scenario=='page-two'&&req.paging.offset.text()!='1001'))rows=('<transaction><transId>999</transId><invoiceNumber>other</invoiceNumber></transaction>'*1000)
  "<response><messages><resultCode>Ok</resultCode></messages><totalNumInResultSet>${scenario=='cap'?20001:(scenario=='page-two'?1001:(match?1:0))}</totalNumInResultSet><transactions>${rows}</transactions></response>"
 }
 f
}
def run={f,Map input=[:]-> f.run('service/AuthorizeDotNet/HostedServices.xml','confirm','HostedTransaction',[orderId:'synthetic-order',orderPartSeqId:'01',paymentGatewayConfigId:'synthetic-gateway',transId:null]+input)}
check('routine empty fallback costs exactly one request and never scans settled history'){
 def f=fixture('empty');def r=run(f,[lookupMode:'routine']);assert !r.authorized;assert f.lookupRequests.size()==1;assert r.gatewayCallCount==1;assert f.tables['mantle.account.payment.Payment'].empty
}
check('routine found invoice returns only an untrusted hint within one request'){
 def f=fixture('found');def r=run(f,[lookupMode:'routine']);assert !r.authorized;assert r.transactionHint=='123456789';assert f.lookupRequests.size()==1;assert f.tables['mantle.account.payment.Payment'].empty
}
check('hint confirmation in a separate poll uses exactly one authoritative details request'){
 def f=fixture('found');def r=run(f,[lookupMode:'routine',transId:'123456789']);assert r.authorized;assert f.lookupRequests*.op.every{it.endsWith('getTransactionDetailsRequest')};assert r.gatewayCallCount==1;assert f.tables['mantle.account.payment.Payment'].size()==1
}
check('explicit legacy recovery retains settled lookup and full verification'){
 def f=fixture('settled');def r=run(f);assert r.authorized;assert f.lookupRequests.size()==4;assert r.gatewayCallCount==4
}
check('routine pagination is bounded to the first page even when more rows exist'){
 def f=fixture('page-two');def r=run(f,[lookupMode:'routine']);assert !r.authorized;assert !r.transactionHint;assert f.lookupRequests.size()==1;assert f.lookupRequests[0].offset=='1'
}
check('explicit recovery follows pagination and verifies the matching transaction'){
 def f=fixture('page-two');def r=run(f,[lookupMode:'recovery']);assert r.authorized;assert f.lookupRequests*.offset.contains('1001');assert r.gatewayCallCount==3
}
check('forged hint still cannot bypass existing invoice correlation'){
 def f=fixture('foreign');def r=run(f,[lookupMode:'routine',transId:'123456789']);assert f.errors;assert f.tables['mantle.account.payment.Payment'].empty;assert f.lookupRequests.size()==1
}
['gateway-error','malformed','http-error'].each{scenario->check('routine '+scenario+' never records payment or authorizes retry'){
 def f=fixture(scenario);try{def r=run(f,[lookupMode:'routine']);assert !r.authorized}catch(Exception ignored){}
 assert f.lookupRequests.size()==1;assert f.tables['mantle.account.payment.Payment'].empty
}}
check('diagnostics count every request and contain no invoice, credential or payload'){
 def f=fixture('settled');run(f);def logs=f.lookupLogs.findAll{it.startsWith('HOSTED_CONFIRM_GATEWAY_CALL ')};assert logs.size()==4
 assert logs.last().contains('call_count=4');assert logs.every{!it.contains('SYNTHETIC')&&!it.contains('synthetic-order')&&!it.contains('<')&&!it.contains('123456789')}
}
check('unknown lookup mode is rejected before any gateway request'){
 def f=fixture('empty');def r=run(f,[lookupMode:'invalid']);assert f.errors;assert f.lookupRequests.empty;assert f.tables['mantle.account.payment.Payment'].empty
}
check('verified hint replay retains a single canonical payment'){
 def f=fixture('found');run(f,[lookupMode:'routine',transId:'123456789']);run(f,[lookupMode:'routine',transId:'123456789']);assert f.tables['mantle.account.payment.Payment'].size()==1
}
check('full recovery safety caps include every page and still return uncertainty'){
 def f=fixture('cap');def r=run(f,[lookupMode:'recovery']);assert !r.authorized;assert !r.transactionHint
 assert f.lookupRequests.size()==41;assert r.gatewayCallCount==41;assert f.tables['mantle.account.payment.Payment'].empty
}
println "Hosted lookup: ${passed} passed, ${failures.size()} failed";assert failures.empty:failures
