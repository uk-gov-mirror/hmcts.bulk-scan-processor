package uk.gov.hmcts.reform.bulkscanprocessor.model.out.msg;


public interface Msg {

    String getMsgId();

    byte[] getMsgBody();

    boolean isTestOnly();
    
}