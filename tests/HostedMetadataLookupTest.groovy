import org.moqui.util.MNode
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
this.class.classLoader.parseClass(new File('tests/HostedPaymentInstrumentTest.groovy'))

// Compose the real lookup, details parser, confirmation and Mantle XML in one
// invocation. Only external HTTP and entity persistence are map-backed doubles.
int passed=0;def failures=[]
def check={String name,Closure body->try{body();passed++;println 'PASS '+name}catch(Throwable e){failures<<name;println 'FAIL '+name+' ['+e.class.simpleName+': '+e.message+']'}}
def defaults=[authCode:'TST123',AVSResponse:'Y',cardCodeResponse:'M']
def fixture={Map options=[:]->
 def f=this.class.classLoader.loadClass('InstrumentFixture').getDeclaredConstructor().newInstance()
 f.metaClass.requests=[]
 f.metaClass.transport={String url,String body->
  assert url=='https://example.invalid/never-contact'
  def request=new groovy.xml.XmlParser().parseText(body)
  def op=['getUnsettledTransactionListRequest','getSettledBatchListRequest','getTransactionListRequest','getTransactionDetailsRequest'].find{request.name().toString().endsWith(it)}
  assert op;f.requests<<[op:op,offset:request.paging.offset.text()]
  if(op=='getTransactionDetailsRequest'){
   assert request.transId.text()=='123456789'
   def writer=new StringWriter();def xml=new groovy.xml.MarkupBuilder(writer)
   xml.response {
    messages {resultCode('Ok')}
    transaction {
     if(options.statusXml)options.statusXml.call(xml)
     else transactionStatus(f.txnStatus)
     responseCode(f.reason);order {invoiceNumber(f.invoice)};settleAmount(f.amount)
     (options.containsKey('metadata')?options.metadata:defaults).each{k,v->xml."${k}"(v)}
     options.extra?.call(xml)
    }
   }
   return writer.toString()
  }
  if(op=='getSettledBatchListRequest')return '<response><messages><resultCode>Ok</resultCode></messages><batchList><batch><batchId>synthetic-batch</batchId></batch></batchList></response>'
  boolean match=!(options.path in ['empty','later','settled']) || (options.path=='later'&&request.paging.offset.text()=='1001') || (options.path=='settled'&&op=='getTransactionListRequest')
  String rows=match?'<transaction><transId>123456789</transId><invoiceNumber>synthetic-order</invoiceNumber></transaction>':''
  if(options.path=='later'&&!match)rows='<transaction><transId>999</transId><invoiceNumber>other</invoiceNumber></transaction>'*1000
  int total=options.path=='later'?1001:match?1:0
  "<response><messages><resultCode>Ok</resultCode></messages><totalNumInResultSet>${total}</totalNumInResultSet><transactions>${rows}</transactions></response>"
 }
 f
}
def run={f,Map input=[:]->f.run('service/AuthorizeDotNet/HostedServices.xml','confirm','HostedTransaction',
 [orderId:'synthetic-order',orderPartSeqId:'01',paymentGatewayConfigId:'synthetic-gateway',transId:null]+input)}
def seq={f,r,List expected->assert f.requests*.op==expected;assert r.gatewayCallCount==expected.size()}
def list='getUnsettledTransactionListRequest',details='getTransactionDetailsRequest',batches='getSettledBatchListRequest',batch='getTransactionListRequest'
def metadataFields=['approvalCode','avsResult','cvResult','transactionStatus']
def assertNew={f,r->
 assert r.authorized&&f.errors.empty
 assert f.tables['mantle.account.payment.Payment'].size()==1&&f.tables['mantle.account.method.PaymentGatewayResponse'].size()==1
 assert f.payment().subMap(['orderId','orderPartSeqId','fromPartyId','amount','amountUomId','paymentRefNum','paymentAuthCode'])==
  [orderId:'synthetic-order',orderPartSeqId:'01',fromPartyId:'synthetic-customer',amount:75G,amountUomId:'USD',paymentRefNum:'123456789',paymentAuthCode:'TST123']
 assert f.tables['mantle.account.method.PaymentGatewayResponse'][0].subMap(metadataFields)==[approvalCode:'TST123',avsResult:'Y',cvResult:'M',transactionStatus:f.txnStatus]
 assert f.events.contains('lock:mantle.order.OrderHeader')
}
check('composed routine no match: one list, uncertain, no payment or response'){
 def f=fixture([path:'empty']);def r=run(f,[lookupMode:'routine']);seq(f,r,[list]);assert !r.authorized
 assert f.tables['mantle.account.payment.Payment'].empty&&f.tables['mantle.account.method.PaymentGatewayResponse'].empty
}
def routeCases=[['routine',[:],[list,details]],['callback',[transId:'123456789'],[details]],['later',[lookupMode:'recovery'],[list,list,details]],['settled',[:],[list,batches,batch,details]]]
routeCases.each { entry ->
 check('composed '+entry[0]+': exact requests, verified outcome and metadata'){
  def f=fixture([path:entry[0]]);def r=run(f,[lookupMode:'routine']+(entry[0]=='settled' ? [lookupMode:'recovery']:[:])+entry[1]);seq(f,r,entry[2]);assertNew(f,r)
 }
}
;['later','settled'].each{path->check('routine '+path+' remains uncertain until full manual recovery'){
 def f=fixture([path:path]);def r=run(f,[lookupMode:'routine']);seq(f,r,[list]);assert !r.authorized&&f.tables['mantle.account.payment.Payment'].empty
 f.requests.clear();r=run(f);seq(f,r,path=='later' ? [list,list,details]:[list,batches,batch,details]);assertNew(f,r)
}}
;['routine','callback'].each{path->
 ['status','invoice','amount'].each{bad->check('composed '+path+' rejects '+bad+' without metadata/payment writes'){
  def f=fixture();if(bad=='status')f.txnStatus='underReview';if(bad=='invoice')f.invoice='other-order';if(bad=='amount')f.amount='76.00'
  def r=run(f,[lookupMode:'routine']+(path=='callback' ? [transId:'123456789']:[:]))
  seq(f,r,path=='callback' ? [details]:[list,details]);assert !r.authorized||f.errors
  assert f.tables['mantle.account.payment.Payment'].empty&&f.tables['mantle.account.method.PaymentGatewayResponse'].empty
 }}
}
check('caller fields and nested metadata cannot replace server evidence or ownership'){
 def f=fixture();def r=run(f,[lookupMode:'routine',paymentAuthCode:'FAKE00',approvalCode:'FAKE00',avsResult:'N',cvResult:'N',
 transactionStatus:'declined',customerPartyId:'foreign-customer',hostedPaymentMetadata:[approvalCode:'FAKE00',rawCard:'NEVER'],newHostedPayment:true])
 seq(f,r,[list,details]);assertNew(f,r)
}
check('missing server metadata never falls back to caller values or adds requests'){
 def f=fixture([metadata:[:]]);def r=run(f,[lookupMode:'routine',paymentAuthCode:'FAKE00',approvalCode:'FAKE00',avsResult:'N',cvResult:'N'])
 seq(f,r,[list,details]);assert r.authorized&&!f.payment().paymentAuthCode
 def response=f.tables['mantle.account.method.PaymentGatewayResponse'][0];assert !response.approvalCode&&!response.avsResult&&!response.cvResult
}
;[malformed:[authCode:'TOO-LONG',AVSResponse:'?',cardCodeResponse:'UNKNOWN'],nested:[authCode:{unexpected('TST123')},AVSResponse:{unexpected('Y')},cardCodeResponse:{unexpected('M')}]].each{kind,values->
 check('composed '+kind+' optional metadata preserves decision and two-request cost'){
  def f=fixture([metadata:values]);def r=run(f,[lookupMode:'routine']);seq(f,r,[list,details]);assert r.authorized&&!f.payment().paymentAuthCode
  def response=f.tables['mantle.account.method.PaymentGatewayResponse'][0];assert !response.approvalCode&&!response.avsResult&&!response.cvResult
 }
}
check('composed duplicate optional elements are omitted, with no added details call'){
 def f=fixture([extra:{xml->xml.authCode('TST999');xml.AVSResponse('N');xml.cardCodeResponse('N')}]);def r=run(f,[lookupMode:'routine'])
 seq(f,r,[list,details]);assert r.authorized&&!f.payment().paymentAuthCode
 def p=f.tables['mantle.account.method.PaymentGatewayResponse'][0];assert !p.approvalCode&&!p.avsResult&&!p.cvResult
}
;[nested:{xml->xml.transactionStatus {unexpected('capturedPendingSettlement')}},duplicate:{xml->xml.transactionStatus('capturedPendingSettlement');xml.transactionStatus('')}].each{kind,shape->
 check('optional '+kind+' status is not persisted; original financial decision unchanged'){
  def f=fixture([statusXml:shape]);def r=run(f,[lookupMode:'routine']);seq(f,r,[list,details]);assert r.authorized
  assert !f.tables['mantle.account.method.PaymentGatewayResponse'][0].containsKey('transactionStatus')
 }
}
check('cross-route replay preserves canonical payment/response and original metadata'){
 def values=new LinkedHashMap(defaults);def f=fixture([metadata:values]);assertNew(f,run(f,[lookupMode:'routine']));def payment=new LinkedHashMap(f.payment());def response=new LinkedHashMap(f.tables['mantle.account.method.PaymentGatewayResponse'][0])
 values.putAll([authCode:'TST999',AVSResponse:'N',cardCodeResponse:'N'])
 [[transId:'123456789'],[lookupMode:'recovery'],[lookupMode:'routine']].each{input->
  f.requests.clear();f.calls.clear();def r=run(f,input);seq(f,r,input.transId ? [details]:[list,details]);assert r.authorized
  assert f.tables['mantle.account.payment.Payment']==[payment]&&f.tables['mantle.account.method.PaymentGatewayResponse']==[response]
  assert !f.calls.any{it.service.startsWith('create#mantle.account.')||it.service=='update#mantle.account.payment.Payment'}
 }
}
;['PmntAuthorized','PmntDelivered','PmntConfirmed','PmntPromised'].each{status->
 [false,true].each{existingResponse->check('historical '+status+' response='+existingResponse+' advances only per existing contract; no backfill'){
  def f=fixture();def old=[paymentId:'historical-payment',orderId:'synthetic-order',orderPartSeqId:'01',statusId:status,paymentAuthCode:'OLD000',paymentInstrumentEnumId:'PiCompanyCheck',paymentRefNum:'old-reference',amount:75G,amountUomId:'USD']
  f.tables['mantle.account.payment.Payment']<<new LinkedHashMap(old)
  def oldResponse=[paymentId:'historical-payment',referenceNum:'123456789',approvalCode:'OLD000',avsResult:'N',cvResult:'N',transactionStatus:'historical']
  if(existingResponse)f.tables['mantle.account.method.PaymentGatewayResponse']<<new LinkedHashMap(oldResponse)
  def r=run(f,[lookupMode:'routine']);seq(f,r,[list,details]);assert r.authorized&&f.payment().paymentAuthCode=='OLD000'
  assert f.tables['mantle.account.payment.Payment'].size()==1
  assert f.payment().statusId==(status=='PmntPromised'?'PmntDelivered':status)
  def response=f.tables['mantle.account.method.PaymentGatewayResponse'][0]
  if(existingResponse)assert response==oldResponse
  else assert !response.keySet().any{it in metadataFields}
  assert f.calls.count{it.service=='update#mantle.account.payment.Payment'}==(status=='PmntPromised'?1:0)
 }}
}
check('schema-backed response write rejects unknown entity fields before persistence'){
 def f=fixture();boolean rejected=false
 try{f.call('create#mantle.account.method.PaymentGatewayResponse',[paymentId:'synthetic-payment',madeUpMetadata:'NEVER'])}catch(AssertionError e){rejected=true}
 assert rejected&&f.tables['mantle.account.method.PaymentGatewayResponse'].empty
}
println "Hosted composed metadata/lookup: ${passed} passed, ${failures.size()} failed";assert failures.empty:failures
