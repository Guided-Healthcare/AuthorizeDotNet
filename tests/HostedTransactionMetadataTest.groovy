import org.moqui.util.MNode
this.class.classLoader.parseClass(new File(System.getenv('XML_SERVICE_HARNESS')))
this.class.classLoader.parseClass(new File('tests/HostedPaymentInstrumentTest.groovy'))

// Real hosted confirmation, HTTP/parser script, Mantle create/auto-info and XML compiler.
// All persistence and JDK HTTP are isolated using the existing synthetic instrument fixture.
def fixture={Map extra=[:],Closure additional=null->
    def f=this.class.classLoader.loadClass('InstrumentFixture').getDeclaredConstructor().newInstance()
    f.metaClass.transport={String url,String body->
        assert url=='https://example.invalid/never-contact'
        def request=new groovy.xml.XmlParser().parseText(body)
        assert request.name().toString().endsWith('getTransactionDetailsRequest')
        assert request.transId.text()=='synthetic-transaction'
        f.events<<'isolated-details'
        def writer=new StringWriter()
        def xml=new groovy.xml.MarkupBuilder(writer)
        xml.getTransactionDetailsResponse {
            messages {resultCode('Ok')}
            transaction {
                transactionStatus(f.txnStatus);responseCode(f.reason)
                order {invoiceNumber(f.invoice)}
                settleAmount(f.amount)
                extra.each{k,v-> xml."${k}"(v)}
                additional?.call(xml)
            }
        }
        String reply=writer.toString()
        if(extra.payment instanceof Closure){
            def card=new groovy.xml.XmlParser().parseText(reply).transaction.payment.creditCard
            assert card.size()==1 && card.cardNumber.text()=='9'.multiply(16)
        }
        reply
    }
    f
}
def metadata=[authCode:'TST123',AVSResponse:'Y',cardCodeResponse:'M']
def response={f->f.tables['mantle.account.method.PaymentGatewayResponse'][0]}
def fields=['approvalCode','avsResult','cvResult','transactionStatus']
int passed=0;List failures=[]
def check={String name,Closure body->try{body();passed++;println 'PASS '+name}catch(Throwable error){failures<<name;println 'FAIL '+name+' ['+error.class.simpleName+']'}}

check('existing schema and Mantle creation contract support selected response fields'){
    def schema=MNode.parse(new File(System.getenv('MANTLE_UDM_ROOT')+'/entity/AccountingAccountEntities.xml'))
    def pgr=schema.children('entity').find{it.attribute('entity-name')=='PaymentGatewayResponse'}
    def payment=schema.children('entity').find{it.attribute('entity-name')=='Payment'}
    assert fields.every{name->pgr.children('field').any{it.attribute('name')==name}}
    assert payment.children('field').any{it.attribute('name')=='paymentAuthCode'}
    def creation=MNode.parse(new File(System.getenv('MANTLE_USL_ROOT')+'/service/mantle/account/PaymentServices.xml'))
        .children('service').find{it.attribute('verb')=='create'&&it.attribute('noun')=='Payment'}
    assert creation.first('in-parameters').children('auto-parameters').any{
        it.attribute('entity-name')=='mantle.account.payment.Payment' && it.attribute('include')=='nonpk' &&
        !it.children('exclude').any{it.attribute('field-name')=='paymentAuthCode'}
    }
}
['authorizedPendingCapture','capturedPendingSettlement','settledSuccessfully'].each{status->
    check('new verified '+status+' payment preserves sanitized gateway metadata'){
        def f=fixture(metadata);f.txnStatus=status;def result=f.confirm()
        assert result.authorized && f.errors.empty
        assert f.payment().paymentAuthCode=='TST123'
        assert response(f).subMap(fields)==[approvalCode:'TST123',avsResult:'Y',cvResult:'M',transactionStatus:status]
        assert f.calls.find{it.service=='mantle.account.PaymentServices.create#Payment'}.input.paymentAuthCode=='TST123'
        assert f.events.count{it=='isolated-details'}==1
    }
}
check('missing optional response codes do not change verified payment authority'){
    def f=fixture();assert f.confirm().authorized && f.errors.empty
    assert !f.payment().paymentAuthCode
    assert response(f).transactionStatus=='capturedPendingSettlement'
    assert !response(f).approvalCode && !response(f).avsResult && !response(f).cvResult
}
check('caller supplied metadata never overrides the server transaction details'){
    def f=fixture(metadata)
    def result=f.run('service/AuthorizeDotNet/HostedServices.xml','confirm','HostedTransaction',
        [orderId:'synthetic-order',orderPartSeqId:'01',transId:'synthetic-transaction',paymentGatewayConfigId:'synthetic-gateway',
         approvalCode:'FAKE00',paymentAuthCode:'FAKE00',avsResult:'Z',cvResult:'N',transactionStatus:'declined'])
    assert result.authorized && f.payment().paymentAuthCode=='TST123' && response(f).approvalCode=='TST123'
    assert response(f).avsResult=='Y' && response(f).cvResult=='M'
}
['declined','underReview','unknown','capturedPendingSettlement'].each{status->
    check('unapproved or unknown response '+status+' stores no payment or metadata'){
        def f=fixture(metadata);f.txnStatus=status;f.reason=status=='capturedPendingSettlement'?'4':'2'
        assert !f.confirm().authorized
        assert f.tables['mantle.account.payment.Payment'].empty && f.tables['mantle.account.method.PaymentGatewayResponse'].empty
    }
}
['wrong-invoice','wrong-amount'].each{kind->
    check('binding failure '+kind+' stores no metadata'){
        def f=fixture(metadata)
        if(kind=='wrong-invoice')f.invoice='synthetic-foreign-order'
        else f.amount='76.00'
        f.confirm()
        assert f.errors && f.tables['mantle.account.payment.Payment'].empty && f.tables['mantle.account.method.PaymentGatewayResponse'].empty
    }
}
check('replayed confirmation neither duplicates nor replaces the original metadata'){
    def server=new LinkedHashMap(metadata);def f=fixture(server);f.confirm()
    assert response(f).approvalCode=='TST123'
    def payment=new LinkedHashMap(f.payment());def pgr=new LinkedHashMap(response(f))
    f.calls.clear();server.putAll([authCode:'TST999',AVSResponse:'N',cardCodeResponse:'N'])
    // Server metadata now differs; previously stored metadata must remain byte-for-byte.
    f.confirm()
    assert f.payment()==payment && response(f)==pgr
    assert f.tables['mantle.account.payment.Payment'].size()==1 && f.tables['mantle.account.method.PaymentGatewayResponse'].size()==1
    assert !f.calls.any{it.service in ['mantle.account.PaymentServices.create#Payment','create#mantle.account.method.PaymentGatewayResponse','update#mantle.account.payment.Payment']}
}
['PmntAuthorized','PmntDelivered','PmntConfirmed'].each{status->
    check('historical '+status+' payment without response is not metadata-backfilled'){
        def f=fixture(metadata)
        Map old=[paymentId:'synthetic-existing',orderId:'synthetic-order',orderPartSeqId:'01',
            statusId:status,paymentInstrumentEnumId:'PiCompanyCheck',paymentAuthCode:'OLD000',
            paymentRefNum:'synthetic-old-reference',amount:75G,amountUomId:'USD']
        f.tables['mantle.account.payment.Payment']<<new LinkedHashMap(old)
        f.confirm()
        assert f.payment()==old
        assert !response(f).keySet().any{it in fields}
        assert !f.calls.any{it.service=='update#mantle.account.payment.Payment'}
    }
}
['A','B','E','G','N','P','R','S','U','W','X','Y','Z'].each{code->
    check('documented AVS response code '+code+' is stored'){
        def f=fixture(metadata+[AVSResponse:code]);f.confirm();assert response(f).avsResult==code
    }
}
['M','N','P','S','U'].each{code->
    check('documented card verification response code '+code+' is stored'){
        def f=fixture(metadata+[cardCodeResponse:code]);f.confirm();assert response(f).cvResult==code
    }
}
[authCode:['short','TOO-LONG','A!B@C#','',null],AVSResponse:['YY','?', 'y','',null],
 cardCodeResponse:['123','UNKNOWN','m','',null]].each{source,values->
    values.eachWithIndex{value,i->
        check('unexpected '+source+' metadata omitted '+i){
            def f=fixture(metadata+[(source):value]);def result=f.confirm()
            assert result.authorized && f.errors.empty
            String target=[authCode:'approvalCode',AVSResponse:'avsResult',cardCodeResponse:'cvResult'][source]
            assert !response(f)[target]
            if(source=='authCode')assert !f.payment().paymentAuthCode
        }
    }
}
['authCode','AVSResponse','cardCodeResponse'].each{source->
    check('nested response-code content omitted '+source){
        def f=fixture(metadata+[(source):{unexpected('SYNTHETIC-NESTED')}]);assert f.confirm().authorized
        String target=[authCode:'approvalCode',AVSResponse:'avsResult',cardCodeResponse:'cvResult'][source]
        assert !response(f)[target] && response(f).transactionStatus=='capturedPendingSettlement'
        if(source=='authCode')assert !f.payment().paymentAuthCode
    }
}
['authCode','AVSResponse','cardCodeResponse'].each{source->
    check('duplicate response-code elements omitted '+source){
        def base=new LinkedHashMap(metadata);base.remove(source)
        def f=fixture(base,{xml->
            xml."${source}"(source=='authCode'?'TST':'')
            xml."${source}"(source=='authCode'?'123':source=='AVSResponse'?'Y':'M')
        })
        assert f.confirm().authorized
        String target=[authCode:'approvalCode',AVSResponse:'avsResult',cardCodeResponse:'cvResult'][source]
        assert !response(f)[target] && response(f).transactionStatus=='capturedPendingSettlement'
        if(source=='authCode')assert !f.payment().paymentAuthCode
    }
}
check('unexpected card and raw response fields never enter persisted metadata'){
    def f=fixture(metadata+[payment:{creditCard{cardNumber('9'.multiply(16));cardType('SYNTHETIC-UNEXPECTED-TYPE');expirationDate('SYNTHETIC-EXPIRY');cardCode('9'.multiply(3))}},
        responseReasonDescription:'SYNTHETIC-RAW-RESPONSE',billTo:{firstName('SYNTHETIC-PII')}])
    assert f.confirm().authorized && f.errors.empty
    def writes=f.calls.findAll{it.service in ['create#mantle.account.payment.Payment','create#mantle.account.method.PaymentGatewayResponse']}
    assert writes.size()==2
    assert !writes.toString().contains('SYNTHETIC-RAW-RESPONSE') && !writes.toString().contains('SYNTHETIC-PII')
    assert !writes.toString().contains('SYNTHETIC-EXPIRY') && !writes.toString().contains('SYNTHETIC-UNEXPECTED-TYPE')
    assert !writes.toString().contains('9'.multiply(16)) && !writes.toString().contains('9'.multiply(3))
    assert !writes.any{it.input.keySet().any{k->k in ['cardNumber','cardType','expirationDate','cardCode','responseString','responseReasonDescription','billTo','payment']}}
}
println "Hosted transaction metadata: ${passed} passed, ${failures.size()} failed"
assert failures.empty:failures.join('; ')
