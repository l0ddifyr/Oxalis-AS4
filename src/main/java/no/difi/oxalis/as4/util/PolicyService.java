package no.difi.oxalis.as4.util;

import no.difi.oxalis.as4.lang.OxalisAs4TransmissionException;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.ws.policy.PolicyBuilder;
import org.apache.neethi.Policy;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;

public class PolicyService {

    private final String policyClasspath;

    public PolicyService(String policyClasspath) {
        this.policyClasspath = policyClasspath;
    }

    public Policy getPolicy() throws OxalisAs4TransmissionException {
        Bus bus = BusFactory.getDefaultBus();
        new OxalisAlgorithmSuiteLoader(bus);
        return getPolicy(bus);
    }

    public Policy getPolicy(Bus bus) throws OxalisAs4TransmissionException {
        try {
            InputStream policyStream = PolicyService.class.getResourceAsStream(policyClasspath);
            PolicyBuilder builder = bus.getExtension(PolicyBuilder.class);
            return builder.getPolicy(policyStream);
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new OxalisAs4TransmissionException("Failed to get WS Policy", e);
        }
    }
}
