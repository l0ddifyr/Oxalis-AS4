package no.difi.oxalis.as4.outbound;

import com.google.common.collect.Lists;
import no.difi.oxalis.api.outbound.TransmissionRequest;
import no.difi.oxalis.as4.api.MessageIdGenerator;
import no.difi.oxalis.as4.util.CompressionUtil;
import no.difi.oxalis.as4.util.Constants;
import no.difi.oxalis.as4.util.Marshalling;
import no.difi.oxalis.as4.util.MessageIdUtil;
import no.difi.oxalis.commons.security.CertificateUtils;
import org.apache.cxf.attachment.AttachmentUtil;
import org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.*;
import org.springframework.http.MediaType;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.mime.Attachment;
import org.springframework.ws.soap.SoapHeader;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessage;

import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static no.difi.oxalis.as4.util.Constants.*;

public class As4Sender implements WebServiceMessageCallback {

    private final TransmissionRequest request;
    private final X509Certificate certificate;
    private final CompressionUtil compressionUtil;
    private final MessageIdGenerator messageIdGenerator;

    As4Sender(TransmissionRequest request, X509Certificate certificate, CompressionUtil compressionUtil, MessageIdGenerator messageIdGenerator) {
        this.request = request;
        this.certificate = certificate;
        this.compressionUtil = compressionUtil;
        this.messageIdGenerator = messageIdGenerator;
    }

    @Override
    public void doWithMessage(WebServiceMessage webServiceMessage) throws IOException, TransformerException {
        SaajSoapMessage message = (SaajSoapMessage) webServiceMessage;

        InputStream compressedAttachment = compressionUtil.getCompressedStream(request.getPayload());

        // Must be octet-stream for encrypted attachments
        message.addAttachment(MessageIdUtil.wrap(getPayloadHref()), () -> compressedAttachment, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        addEbmsHeader(message);
    }

    private void addEbmsHeader(SoapMessage message) {
        SoapHeader header = message.getSoapHeader();
        SoapHeaderElement messagingHeader = header.addHeaderElement(Constants.MESSAGING_QNAME);
        messagingHeader.setMustUnderstand(true);

        UserMessage userMessage = UserMessage.builder()
                .withMessageInfo(createMessageInfo())
                .withPartyInfo(createPartyInfo())
                .withCollaborationInfo(createCollaborationInfo())
                .withMessageProperties(createMessageProperties())
                .withPayloadInfo(createPayloadInfo(message))
                .build();

        JAXBElement<UserMessage> userMessageJAXBElement = new JAXBElement<>(Constants.USER_MESSAGE_QNAME,
                (Class<UserMessage>) userMessage.getClass(), userMessage);
        Jaxb2Marshaller marshaller = Marshalling.getInstance();
        marshaller.marshal(userMessageJAXBElement, messagingHeader.getResult());
    }

    private PayloadInfo createPayloadInfo(SoapMessage message) {
        Iterator<Attachment> attachments = message.getAttachments();
        ArrayList<PartInfo> partInfos = Lists.newArrayList();
        while (attachments.hasNext()) {
            Attachment a = attachments.next();
            String cid = "cid:" + AttachmentUtil.cleanContentId(a.getContentId());
            Property compressionType = Property.builder()
                    .withName("CompressionType")
                    .withValue("application/gzip")
                    .build();
            Property mimeType = Property.builder()
                    .withName("MimeType")
                    .withValue("application/xml")
                    .build();
            PartProperties partProperties = PartProperties.builder().withProperty(compressionType, mimeType).build();
            PartInfo partInfo = PartInfo.builder()
                    .withHref(cid)
                    .withPartProperties(partProperties)
                    .build();
            partInfos.add(partInfo);
        }

        return PayloadInfo.builder()
                .withPartInfo(partInfos)
                .build();
    }

    private MessageProperties createMessageProperties() {
        Map<String, String> properties = new HashMap<>();

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            if (as4TransmissionRequest.getMessageProperties() != null) {
                properties.putAll(as4TransmissionRequest.getMessageProperties());
            }
        }

        if (!properties.containsKey("originalSender")) {
            properties.put("originalSender", request.getHeader().getSender().getIdentifier());
        }

        if (!properties.containsKey("finalRecipient")) {
            properties.put("finalRecipient", request.getHeader().getReceiver().getIdentifier());
        }

        return MessageProperties.builder()
                .withProperty(properties.entrySet().stream()
                        .map(p -> Property.builder()
                                .withName(p.getKey())
                                .withValue(p.getValue())
                                .build())
                        .collect(Collectors.toList())
                )
                .build();
    }

    private PartyInfo createPartyInfo() {

        String fromName = CertificateUtils.extractCommonName(certificate);
        String toName = CertificateUtils.extractCommonName(request.getEndpoint().getCertificate());

        return PartyInfo.builder()
                .withFrom(From.builder()
                        .withPartyId(PartyId.builder()
                                .withType(PARTY_ID_TYPE)
                                .withValue(fromName)
                                .build())
                        .withRole(FROM_ROLE)
                        .build())
                .withTo(To.builder()
                        .withPartyId(PartyId.builder()
                                .withType(PARTY_ID_TYPE)
                                .withValue(toName)
                                .build())
                        .withRole(TO_ROLE)
                        .build()
                ).build();
    }

    private CollaborationInfo createCollaborationInfo() {
        CollaborationInfo.Builder cib = CollaborationInfo.builder()
                .withConversationId(getConversationId())
                .withAction(request.getHeader().getDocumentType().toString())
                .withService(Service.builder()
                        .withType(SERVICE_TYPE)
                        .withValue(request.getHeader().getProcess().getIdentifier())
                        .build()
                );

        if (AGREEMENT_REF != null) {
            cib = cib.withAgreementRef(AgreementRef.builder()
                    .withValue(AGREEMENT_REF)
                    .build()
            );
        }

        return cib.build();
    }

    private MessageInfo createMessageInfo() {
        GregorianCalendar gcal = GregorianCalendar.from(LocalDateTime.now().atZone(ZoneId.systemDefault()));
        XMLGregorianCalendar xmlDate;
        try {
            xmlDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(gcal);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException("Error getting xml date", e);
        }

        MessageInfo.Builder builder = MessageInfo.builder()
                .withMessageId(getMessageId())
                .withTimestamp(xmlDate);

        if( request instanceof As4TransmissionRequest ){
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            if( as4TransmissionRequest.getRefToMessageId() != null ){
                builder.withRefToMessageId( as4TransmissionRequest.getRefToMessageId() );
            }
        }

        return builder.build();
    }

    private String getMessageId() {
        String messageId = null;

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            messageId = as4TransmissionRequest.getMessageId();
        }

        return messageId != null ? messageId : newId();
    }

    private String getConversationId() {
        String conversationId = null;

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            conversationId = as4TransmissionRequest.getConversationId();
        }

        return conversationId != null ? conversationId : newId();
    }

    private String getPayloadHref() {
        String payloadHref = null;

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            payloadHref = as4TransmissionRequest.getPayloadHref();
        }

        return payloadHref != null ? payloadHref : newId();
    }

    private String newId() {
        return messageIdGenerator.generate();
    }
}
