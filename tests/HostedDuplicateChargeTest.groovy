// GH-171 step 3: reversing a SECOND successful charge on one order. Production XML, synthetic transport.
// The keeper (the order's bound Payment) is NEVER touched; every guard fails closed.
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
this.class.classLoader.parseClass(new File('tests/HostedPaymentInstrumentTest.groovy'))
int passed=0;def failures=[]
def check={String name,Closure body->try{body();passed++;println 'PASS '+name}catch(Throwable e){failures<<name;println 'FAIL '+name+' ['+e.class.simpleName+': '+e.message+']'}}
final String DUP='AuthorizeDotNet.HostedDuplicateCharge'
// cfg.unsettled / cfg.settled: rows [transId,invoice,status]; cfg.details[transId]: overrides of the default good charge;
// cfg.reversal: 'ok' (default) | 'declined' | 'error'
def fixture={Map cfg->
 def f=this.class.classLoader.loadClass('InstrumentFixture').getDeclaredConstructor().newInstance()
 f.metaClass.requests=[];f.metaClass.reversals=[]
 f.ec.logger.log={Object... args->}
 f.tables['mantle.account.payment.Payment']<<[paymentId:'keeper-payment',orderId:'synthetic-order',orderPartSeqId:'01',statusId:'PmntDelivered',paymentRefNum:'K',paymentGatewayConfigId:'synthetic-gateway']
 f.ec.user.getPreference={String key->key=='HostedDuplicateChargeAction'?(cfg.mode?:'dry-run'):'synthetic-gateway'}
 def row={Map r->"<transaction><transId>${r.transId}</transId><invoiceNumber>${r.invoice}</invoiceNumber>"+(r.status?"<transactionStatus>${r.status}</transactionStatus>":'')+"<submitTimeUTC>${r.at?:'2026-11-10T10:00:00Z'}</submitTimeUTC></transaction>"}
 f.metaClass.transport={String url,String body->
  assert url=='https://example.invalid/never-contact'
  def req=new groovy.xml.XmlParser().parseText(body);String op=req.name().toString();f.requests<<op
  if(cfg.failList&&op.endsWith('getUnsettledTransactionListRequest'))return '<response><messages><resultCode>Error</resultCode></messages></response>'
  if(op.endsWith('createTransactionRequest')){
   f.reversals<<[type:req.transactionRequest.transactionType.text(),ref:req.transactionRequest.refTransId.text(),amount:req.transactionRequest.amount.text(),card:req.transactionRequest.payment.creditCard.cardNumber.text(),exp:req.transactionRequest.payment.creditCard.expirationDate.text()]
   if(cfg.reversal=='error')return '<response><messages><resultCode>Error</resultCode></messages></response>'
   return "<response><messages><resultCode>Ok</resultCode></messages><transactionResponse><responseCode>${cfg.reversal=='declined'?'3':'1'}</responseCode></transactionResponse></response>"
  }
  if(op.endsWith('getTransactionDetailsRequest')){
   String tid=req.transId.text()
   def d=[status:'capturedPendingSettlement',invoice:'synthetic-order',type:'authCaptureTransaction',code:'1',amount:'75.00',card:'XXXX1111']+((cfg.details?:[:])[tid]?:[:])
   return "<response><messages><resultCode>Ok</resultCode></messages><transaction><transactionType>${d.type}</transactionType><transactionStatus>${d.status}</transactionStatus><responseCode>${d.code}</responseCode><order><invoiceNumber>${d.invoice}</invoiceNumber></order><settleAmount>${d.amount}</settleAmount><payment><creditCard><cardNumber>${d.card}</cardNumber></creditCard></payment></transaction></response>"
  }
  if(op.endsWith('getSettledBatchListRequest'))return '<response><messages><resultCode>Ok</resultCode></messages><batchList><batch><batchId>b1</batchId></batch></batchList></response>'
  List rows=op.endsWith('getUnsettledTransactionListRequest')?(cfg.unsettled?:[]):(cfg.settled?:[])
  "<response><messages><resultCode>Ok</resultCode></messages><totalNumInResultSet>${rows.size()}</totalNumInResultSet><transactions>${rows.collect{row(it)}.join('')}</transactions></response>"
 }
 f
}
def reconcile={f->f.run('service/AuthorizeDotNet/HostedServices.xml','reconcile','HostedDuplicateCharges',[orderId:'synthetic-order',orderPartSeqId:'01',paymentGatewayConfigId:'synthetic-gateway'])}
def at={f,long ms->f.ec.user.nowTimestamp=new java.sql.Timestamp(ms)}
def twoCharges=[unsettled:[[transId:'K',invoice:'synthetic-order',status:'capturedPendingSettlement'],[transId:'X',invoice:'synthetic-order-2',status:'capturedPendingSettlement']],
                details:['X':[invoice:'synthetic-order-2']]]
def dups={f->f.tables[DUP]?:[]}

check('one charge only: nothing is observed, recorded or reversed'){
 def f=fixture([mode:'live',unsettled:[[transId:'K',invoice:'synthetic-order',status:'capturedPendingSettlement']]]);def r=reconcile(f)
 assert r.disposition=='none'&&r.duplicateCount==0&&dups(f).empty&&f.reversals.empty
}
check('a declined or voided extra attempt is not a duplicate charge'){
 def f=fixture([mode:'live',unsettled:[[transId:'K',invoice:'synthetic-order',status:'capturedPendingSettlement'],[transId:'D',invoice:'synthetic-order-2',status:'declined'],[transId:'V',invoice:'synthetic-order-3',status:'voided']]])
 def r=reconcile(f);assert r.duplicateCount==0&&dups(f).empty&&f.reversals.empty
}
check('first sighting only records Observed; money never moves on the first scan, even in live mode'){
 def f=fixture(twoCharges+[mode:'live']);at(f,1000000);def r=reconcile(f)
 assert r.disposition=='observed'&&r.duplicateCount==1&&dups(f).size()==1&&dups(f)[0].disposition=='Observed'&&dups(f)[0].keeperTransId=='K'&&dups(f)[0].transId=='X'
 assert f.reversals.empty
}
check('a sighting seen again less than two minutes later still only observes'){
 def f=fixture(twoCharges+[mode:'live']);at(f,1000000);reconcile(f);at(f,1000000+119000);def r=reconcile(f)
 assert r.disposition=='observed'&&f.reversals.empty&&dups(f)[0].disposition=='Observed'
}
check('dry-run default and explicit dry-run record DryRun but never reverse anything'){
 [null,'dry-run','bogus'].each{mode->
  def f=fixture(twoCharges+[mode:mode]);at(f,1000000);reconcile(f);at(f,1000000+121000);def r=reconcile(f)
  assert r.disposition=='dry-run'&&dups(f)[0].disposition=='DryRun'&&f.reversals.empty&&r.actedCount==0
 }
}
check('mode off performs no gateway request at all'){
 def f=fixture(twoCharges+[mode:'off']);def r=reconcile(f);assert r.disposition=='off'&&f.requests.empty&&dups(f).empty
}
check('live: the second agreeing scan voids ONLY the extra unsettled charge, never the keeper'){
 def f=fixture(twoCharges+[mode:'live']);at(f,1000000);reconcile(f);at(f,1000000+121000);def r=reconcile(f)
 assert r.disposition=='acted'&&r.actedCount==1
 assert f.reversals==[[type:'voidTransaction',ref:'X',amount:'',card:'',exp:'']]
 assert dups(f)[0].disposition=='Voided'&&dups(f)[0].actionResult=='approved'&&dups(f)[0].actionDate
 assert f.tables['mantle.account.payment.Payment'].size()==1&&f.tables['mantle.account.payment.Payment'][0].paymentRefNum=='K'
}
check('live: a settled extra is refunded for the exact amount with only the last four digits'){
 def f=fixture([mode:'live',unsettled:[[transId:'K',invoice:'synthetic-order',status:'capturedPendingSettlement']],settled:[[transId:'X',invoice:'synthetic-order-2',status:'settledSuccessfully']],
                details:['X':[invoice:'synthetic-order-2',status:'settledSuccessfully',card:'XXXX4242']]])
 at(f,1000000);reconcile(f);at(f,1000000+121000);def r=reconcile(f)
 assert r.disposition=='acted'&&f.reversals==[[type:'refundTransaction',ref:'X',amount:'75.00',card:'4242',exp:'XXXX']]
 assert dups(f)[0].disposition=='Refunded'
}
check('a reversed duplicate is never reversed again'){
 def f=fixture(twoCharges+[mode:'live']);at(f,1000000);reconcile(f);at(f,1000000+121000);reconcile(f);f.reversals.clear()
 // the gateway still (wrongly) lists X as a success: the audit row stops a second void
 at(f,1000000+300000);def r=reconcile(f);assert f.reversals.empty&&dups(f).size()==1&&dups(f)[0].disposition=='Voided'
}
check('a declined or errored reversal is recorded Failed and retried by a later run'){
 ['declined','error'].each{kind->
  def f=fixture(twoCharges+[mode:'live',reversal:kind]);at(f,1000000);reconcile(f);at(f,1000000+121000);def r=reconcile(f)
  assert r.actedCount==0&&dups(f)[0].disposition=='Failed'&&dups(f)[0].actionResult==(kind=='declined'?'declined':'gateway-error')
  f.reversals.clear();at(f,1000000+300000);f.metaClass.transport={String u,String b->throw new IllegalStateException('x')}  // transport now fails: still no money moves
  try{reconcile(f)}catch(Exception ignored){}
  assert f.reversals.empty
 }
}
check('an extra bound to a Payment on any order is not a duplicate'){
 def f=fixture(twoCharges+[mode:'live']);f.tables['mantle.account.payment.Payment']<<[paymentId:'other',orderId:'another-order',orderPartSeqId:'01',statusId:'PmntDelivered',paymentRefNum:'X']
 // keeper lookup is per order; the extra is referenced elsewhere
 at(f,1000000);reconcile(f);at(f,1000000+121000);reconcile(f);assert f.reversals.empty&&dups(f).empty
}
check('no single keeper: no Payment, or Payments for different transactions, means no action'){
 def f=fixture(twoCharges+[mode:'live']);f.tables['mantle.account.payment.Payment'].clear()
 assert reconcile(f).disposition=='no-single-keeper'&&f.requests.empty
 def g=fixture(twoCharges+[mode:'live']);g.tables['mantle.account.payment.Payment']<<[paymentId:'p2',orderId:'synthetic-order',orderPartSeqId:'01',statusId:'PmntDelivered',paymentRefNum:'X']
 assert reconcile(g).disposition=='no-single-keeper'&&g.reversals.empty
}
check('cancelled, void and declined Payment rows do not count as a second keeper'){
 ['PmntCancelled','PmntVoid','PmntDeclined'].each{status->
  def f=fixture(twoCharges+[mode:'live']);f.tables['mantle.account.payment.Payment']<<[paymentId:'old',orderId:'synthetic-order',orderPartSeqId:'01',statusId:status,paymentRefNum:'Z']
  at(f,1000000);def r1=reconcile(f);at(f,1000000+121000);def r2=reconcile(f);assert f.reversals.size()==1&&f.reversals[0].ref=='X':(status+' '+r1.disposition+' '+r2.disposition)
 }
}
check('any Payment row, even a cancelled one, that references the extra protects it from reversal'){
 def f=fixture(twoCharges+[mode:'live']);f.tables['mantle.account.payment.Payment']<<[paymentId:'old',orderId:'synthetic-order',orderPartSeqId:'01',statusId:'PmntCancelled',paymentRefNum:'X']
 at(f,1000000);reconcile(f);at(f,1000000+121000);reconcile(f);assert f.reversals.empty
}
check('the keeper must appear as a success in a complete scan, otherwise nothing happens'){
 def f=fixture([mode:'live',unsettled:[[transId:'X',invoice:'synthetic-order-2',status:'capturedPendingSettlement'],[transId:'Y',invoice:'synthetic-order-3',status:'capturedPendingSettlement']]])
 assert reconcile(f).disposition=='keeper-not-in-scan'&&dups(f).empty&&f.reversals.empty
}
check('an incomplete scan (gateway error) never acts'){
 def f=fixture(twoCharges+[mode:'live',failList:true]);assert reconcile(f).disposition=='scan-incomplete'&&dups(f).empty&&f.reversals.empty
}
check('keeper details that do not verify (wrong amount) stop everything'){
 def f=fixture(twoCharges+[mode:'live',details:['K':[amount:'10.00'],'X':[invoice:'synthetic-order-2']]]);at(f,1000000);def r=reconcile(f)
 assert r.disposition=='keeper-unverified'&&dups(f).empty&&f.reversals.empty
}
check('an extra that fails verification (amount, invoice, type, response) is never reversed'){
 [[amount:'74.00'],[invoice:'someone-else'],[type:'authOnlyTransaction'],[code:'4']].each{bad->
  def f=fixture([mode:'live',unsettled:twoCharges.unsettled,details:['X':[invoice:'synthetic-order-2']+bad]]);at(f,1000000);reconcile(f);at(f,1000000+121000);reconcile(f)
  assert f.reversals.empty:bad
 }
}
check('reconcile never creates or changes a Payment'){
 def f=fixture(twoCharges+[mode:'live']);def before=new ArrayList(f.tables['mantle.account.payment.Payment'].collect{new LinkedHashMap(it)})
 at(f,1000000);reconcile(f);at(f,1000000+121000);reconcile(f);assert f.tables['mantle.account.payment.Payment']==before
}
println "Hosted duplicate charge: ${passed} passed, ${failures.size()} failed";assert failures.empty:failures.join('; ')
