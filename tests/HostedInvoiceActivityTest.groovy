// GH-171 step 1: exhaustive, read-only gateway scan for every attempt on an order, and confirm#HostedTransaction
// that no longer lets a declined/errored/pending attempt hide a later success. Production XML, synthetic transport.
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
this.class.classLoader.parseClass(new File('tests/HostedPaymentInstrumentTest.groovy'))
int passed=0;def failures=[]
def check={String name,Closure body->try{body();passed++;println 'PASS '+name}catch(Throwable e){failures<<name;println 'FAIL '+name+' ['+e.class.simpleName+': '+e.message+']'}}
// rows: [[transId:'1',invoice:'synthetic-order',status:'declined'],...] returned by the UNSETTLED list;
// settled: rows returned by the (single) settled batch. detailsFor: transId -> [status,invoice,type,code,amount]
def fixture={Map cfg->
 def f=this.class.classLoader.loadClass('InstrumentFixture').getDeclaredConstructor().newInstance()
 f.metaClass.lookupRequests=[]
 f.ec.logger.log={Object... args->}
 def row={Map r->"<transaction><transId>${r.transId}</transId><invoiceNumber>${r.invoice}</invoiceNumber>"+(r.status?"<transactionStatus>${r.status}</transactionStatus>":'')+"<submitTimeUTC>${r.at?:'2026-11-10T10:00:00Z'}</submitTimeUTC></transaction>"}
 f.metaClass.transport={String url,String body->
  assert url=='https://example.invalid/never-contact'
  def req=new groovy.xml.XmlParser().parseText(body);String op=req.name().toString();f.lookupRequests<<[op:op,offset:req.paging.offset.text()]
  if(cfg.fail&&op.endsWith(cfg.fail+'Request'))return '<response><messages><resultCode>Error</resultCode></messages></response>'
  if(op.endsWith('getTransactionDetailsRequest')){
   def d=(cfg.detailsFor?:[:])[req.transId.text()]?:[status:'settledSuccessfully',invoice:'synthetic-order',type:'authCaptureTransaction',code:'1',amount:'75.00']
   return "<response><messages><resultCode>Ok</resultCode></messages><transaction><transactionType>${d.type}</transactionType><transactionStatus>${d.status}</transactionStatus><responseCode>${d.code}</responseCode><order><invoiceNumber>${d.invoice}</invoiceNumber></order><settleAmount>${d.amount}</settleAmount></transaction></response>"
  }
  if(op.endsWith('getSettledBatchListRequest'))return '<response><messages><resultCode>Ok</resultCode></messages><batchList><batch><batchId>fixture-batch</batchId></batch></batchList></response>'
  List rows=op.endsWith('getUnsettledTransactionListRequest')?(cfg.unsettled?:[]):(cfg.settled?:[])
  if(cfg.bigUnsettled&&op.endsWith('getUnsettledTransactionListRequest'))rows=(1..1000).collect{[transId:"o${it}",invoice:'other',status:'declined']}
  int total=cfg.total?:rows.size()
  "<response><messages><resultCode>Ok</resultCode></messages><totalNumInResultSet>${total}</totalNumInResultSet><transactions>${rows.collect{row(it)}.join('')}</transactions></response>"
 }
 f
}
def inspect={f,Map input=[:]->f.run('service/AuthorizeDotNet/HostedServices.xml','inspect','HostedInvoiceActivity',[orderId:'synthetic-order',paymentGatewayConfigId:'synthetic-gateway']+input)}
def confirm={f,Map input=[:]->f.run('service/AuthorizeDotNet/HostedServices.xml','confirm','HostedTransaction',[orderId:'synthetic-order',orderPartSeqId:'01',paymentGatewayConfigId:'synthetic-gateway',transId:null]+input)}

check('inspect classifies every attempt by invoice, across unsettled and settled, and is complete'){
 def f=fixture([unsettled:[[transId:'1',invoice:'synthetic-order',status:'declined'],[transId:'2',invoice:'synthetic-order-2',status:'FDSPendingReview'],[transId:'9',invoice:'someone-else',status:'settledSuccessfully']],
                settled:[[transId:'3',invoice:'synthetic-order-3',status:'settledSuccessfully']]])
 def r=inspect(f)
 assert r.activity*.transId==['1','2','3']:r.activity
 assert r.activity*.cls==['negative','uncertain','success']
 assert r.successTransIds==['3'];assert r.hasUncertain;assert r.scanComplete
 assert f.tables['mantle.account.payment.Payment'].empty
}
['synthetic-order2','synthetic-order-','synthetic-order-x','synthetic-order-1234','xsynthetic-order'].each{inv->check('inspect ignores a non-attempt invoice '+inv){
 def r=inspect(fixture([unsettled:[[transId:'1',invoice:inv,status:'settledSuccessfully']]]));assert r.activity.empty&&!r.successTransIds
}}
check('inspect with nothing found is complete in recovery and incomplete in routine'){
 def r=inspect(fixture([:]));assert r.scanComplete&&r.activity.empty
 def f=fixture([:]);r=inspect(f,[lookupMode:'routine']);assert !r.scanComplete;assert f.lookupRequests.size()==1
}
['getUnsettledTransactionList','getSettledBatchList','getTransactionList'].each{op->check('inspect is incomplete (never "no charge") when '+op+' fails'){
 def r=inspect(fixture([fail:op,settled:[[transId:'3',invoice:'synthetic-order',status:'settledSuccessfully']]]))
 assert !r.scanComplete
}}
check('inspect is incomplete when the paging safety cap is reached'){
 def r=inspect(fixture([bigUnsettled:true,total:20001]));assert !r.scanComplete
}
check('inspect stopOnSuccess ends early and is not exhaustive'){
 def f=fixture([unsettled:[[transId:'1',invoice:'synthetic-order',status:'settledSuccessfully']]]);def r=inspect(f,[stopOnSuccess:true])
 assert r.successTransIds==['1'];assert !r.scanComplete;assert f.lookupRequests.size()==1
}
check('inspect statuses: refunds and unknown statuses are uncertain, voided is negative'){
 def r=inspect(fixture([unsettled:[[transId:'1',invoice:'synthetic-order',status:'refundSettledSuccessfully'],[transId:'2',invoice:'synthetic-order',status:'voided'],[transId:'3',invoice:'synthetic-order',status:'couldNotVoid'],[transId:'4',invoice:'synthetic-order']]]))
 assert r.activity.collectEntries{[(it.transId):it.cls]}==['1':'uncertain','2':'negative','3':'uncertain','4':'uncertain']
}
check('inspect rejects an invalid lookup mode before any gateway request'){
 def f=fixture([:]);inspect(f,[lookupMode:'bogus']);assert f.errors;assert f.lookupRequests.empty
}
check('confirm: a declined first attempt no longer hides a later success'){
 def f=fixture([unsettled:[[transId:'1',invoice:'synthetic-order',status:'declined'],[transId:'2',invoice:'synthetic-order',status:'capturedPendingSettlement']]])
 def r=confirm(f);assert r.authorized;assert f.tables['mantle.account.payment.Payment'].size()==1;assert f.tables['mantle.account.payment.Payment'][0].paymentRefNum=='2'
 assert f.lookupRequests*.op.count{it.endsWith('getTransactionDetailsRequest')}==1
}
check('confirm: only negative attempts bind nothing and cost no details request'){
 def f=fixture([unsettled:[[transId:'1',invoice:'synthetic-order',status:'declined'],[transId:'2',invoice:'synthetic-order',status:'generalError']]])
 def r=confirm(f,[lookupMode:'routine']);assert !r.authorized;assert f.tables['mantle.account.payment.Payment'].empty
 assert f.lookupRequests.size()==1
}
check('confirm: a pending-review attempt is verified by details and never bound'){
 def f=fixture([unsettled:[[transId:'1',invoice:'synthetic-order',status:'FDSPendingReview']],detailsFor:['1':[status:'FDSPendingReview',invoice:'synthetic-order',type:'authCaptureTransaction',code:'4',amount:'75.00']]])
 def r=confirm(f,[lookupMode:'routine']);assert !r.authorized;assert f.tables['mantle.account.payment.Payment'].empty
}
check('confirm: two successful attempts bind the earliest only (the extra is left for reconciliation)'){
 def f=fixture([unsettled:[[transId:'2',invoice:'synthetic-order-2',status:'capturedPendingSettlement',at:'2026-11-10T11:00:00Z'],[transId:'1',invoice:'synthetic-order',status:'capturedPendingSettlement',at:'2026-11-10T10:00:00Z']],
                detailsFor:['2':[status:'capturedPendingSettlement',invoice:'synthetic-order-2',type:'authCaptureTransaction',code:'1',amount:'75.00']]])
 confirm(f);assert f.tables['mantle.account.payment.Payment'].size()==1;assert f.tables['mantle.account.payment.Payment'][0].paymentRefNum=='1'
}
check('confirm: a success on a later attempt invoice (orderId-N) binds to the order'){
 def f=fixture([unsettled:[[transId:'2',invoice:'synthetic-order-2',status:'capturedPendingSettlement']],detailsFor:['2':[status:'capturedPendingSettlement',invoice:'synthetic-order-2',type:'authCaptureTransaction',code:'1',amount:'75.00']]])
 def r=confirm(f,[lookupMode:'routine']);assert r.authorized;assert f.tables['mantle.account.payment.Payment'][0].paymentRefNum=='2'
}
check('confirm: a client transId whose invoice is another order or a malformed suffix is still refused'){
 ['someone-else','synthetic-order-x','synthetic-order2'].each{inv->
  def f=fixture([detailsFor:['5':[status:'settledSuccessfully',invoice:inv,type:'authCaptureTransaction',code:'1',amount:'75.00']]])
  confirm(f,[transId:'5']);assert f.tables['mantle.account.payment.Payment'].empty:inv
 }
}
check('confirm: a client transId on a later attempt invoice is accepted'){
 def f=fixture([detailsFor:['5':[status:'settledSuccessfully',invoice:'synthetic-order-2',type:'authCaptureTransaction',code:'1',amount:'75.00']]])
 def r=confirm(f,[transId:'5']);assert r.authorized
}
println "Hosted invoice activity: ${passed} passed, ${failures.size()} failed";assert failures.empty:failures
