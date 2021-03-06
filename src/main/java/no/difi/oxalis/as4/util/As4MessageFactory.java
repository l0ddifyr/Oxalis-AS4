package no.difi.oxalis.as4.util;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import no.difi.oxalis.as4.api.MessageIdGenerator;
import no.difi.oxalis.as4.inbound.ProsessingContext;
import no.difi.oxalis.as4.lang.OxalisAs4Exception;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.ws.policy.PolicyConstants;
import org.oasis_open.docs.ebxml_bp.ebbp_signals_2.MessagePartNRInformation;
import org.oasis_open.docs.ebxml_bp.ebbp_signals_2.NonRepudiationInformation;
import org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.Error;
import org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.soap.*;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class As4MessageFactory {

    private final MessageIdGenerator messageIdGenerator;
    private final MessageFactory messageFactory;
    private final JAXBContext jaxbContext;

    @Inject
    public As4MessageFactory(MessageIdGenerator messageIdGenerator) throws SOAPException {
        this(
                messageIdGenerator,
                MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL),
                Marshalling.getInstance()
        );
    }

    public As4MessageFactory(MessageIdGenerator messageIdGenerator, MessageFactory messageFactory, JAXBContext jaxbContext) {
        this.messageFactory = messageFactory;
        this.jaxbContext = jaxbContext;
        this.messageIdGenerator = messageIdGenerator;
    }

    public SOAPMessage createReceiptMessage(UserMessage inUserMessage, ProsessingContext prosessingContext) throws OxalisAs4Exception {

        XMLGregorianCalendar xmlGc = XMLUtil.dateToXMLGeorgianCalendar(
                prosessingContext.getReceiptTimestamp().getDate()
        );

        MessageInfo messageInfo = MessageInfo.builder()
                .withTimestamp(xmlGc)
                .withMessageId(messageIdGenerator.generate())
                .withRefToMessageId(inUserMessage.getMessageInfo().getMessageId())
                .build();

        List<MessagePartNRInformation> mpList = prosessingContext.getReferenceList().stream()
                .map(reference -> MessagePartNRInformation.builder().withReference(reference).build())
                .collect(Collectors.toList());

        NonRepudiationInformation nri = NonRepudiationInformation.builder()
                .addMessagePartNRInformation(mpList)
                .build();

        SignalMessage signalMessage = SignalMessage.builder()
                .withMessageInfo(messageInfo)
                .withReceipt(Receipt.builder().withAny(nri).build())
                .build();


        return marshalSignalMessage(signalMessage);
    }


    public SOAPMessage createErrorMessage(MessageId messageId, OxalisAs4Exception oxalisException) {
        try {

            XMLGregorianCalendar currentDate = XMLUtil.dateToXMLGeorgianCalendar(new Date());


            MessageInfo messageInfo = MessageInfo.builder()
                    .withRefToMessageId(messageId.getValue())
                    .withTimestamp(currentDate)
                    .withMessageId(messageIdGenerator.generate())
                    .build();

            Error error = Error.builder()
                    .withRefToMessageInError(messageId.getValue())

                    .withErrorCode(oxalisException.getErrorCode().toString())
                    .withErrorDetail(getErrorDetail(oxalisException))
                    .withShortDescription(oxalisException.getErrorCode().getShortDescription())
                    //                .withDescription()

                    .withOrigin(oxalisException.getErrorCode().getOrigin().toString())
                    .withCategory(oxalisException.getErrorCode().getCatgory().toString())
                    .withSeverity(oxalisException.getSeverity().toString())

                    .build();

            SignalMessage signalMessage = SignalMessage.builder()
                    .withMessageInfo(messageInfo)
                    .withError(error)
                    .build();


            return marshalSignalMessage(signalMessage);

        } catch (OxalisAs4Exception e) {
            throw new Fault(e.getCause());
        }
    }

    private String getErrorDetail(OxalisAs4Exception oxalisException) {
        StringBuilder sb = new StringBuilder();

        sb.append(oxalisException.getMessage());

        Throwable throwable = oxalisException;

        while (throwable.getCause() != null) {
            throwable = throwable.getCause();
            sb.append("\ncause: ").append(throwable.getMessage());
        }

        return sb.toString();
    }

    public SOAPMessage marshalSignalMessage(SignalMessage signalMessage) throws OxalisAs4Exception {
        try {
            SOAPMessage message = messageFactory.createMessage();
            SOAPHeader soapHeader = message.getSOAPHeader();

            SOAPHeaderElement messagingHeader = soapHeader.addHeaderElement(Constants.MESSAGING_QNAME);
            messagingHeader.setMustUnderstand(true);

            JAXBElement<SignalMessage> userMessageJAXBElement = new JAXBElement<>(
                    Constants.SIGNAL_MESSAGE_QNAME,
                    SignalMessage.class,
                    signalMessage
            );

            jaxbContext.createMarshaller().marshal(userMessageJAXBElement, messagingHeader);

//            message.setProperty(PolicyConstants.POLICY_OVERRIDE, PolicyUtil.getPolicy());

            return message;

        } catch (Exception e) {
            throw new OxalisAs4Exception("Unable to marshal SignalMessage", e, AS4ErrorCode.EBMS_0004);
        }
    }
}
