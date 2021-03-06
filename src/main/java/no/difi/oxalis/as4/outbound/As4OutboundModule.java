package no.difi.oxalis.as4.outbound;

import com.google.inject.*;
import com.google.inject.name.Names;
import lombok.extern.slf4j.Slf4j;
import no.difi.oxalis.api.outbound.MessageSender;
import no.difi.oxalis.api.settings.Settings;
import no.difi.oxalis.as4.config.As4Conf;
import no.difi.oxalis.as4.inbound.InboundMerlinProvider;
import no.difi.oxalis.as4.util.CompressionUtil;
import no.difi.oxalis.as4.util.OxalisAlgorithmSuiteLoader;
import no.difi.oxalis.as4.util.PeppolConfiguration;
import no.difi.oxalis.as4.util.TransmissionRequestUtil;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static no.difi.oxalis.as4.common.AS4Constants.CEF_CONNECTIVITY;

@Slf4j
public class As4OutboundModule extends AbstractModule {

    @Override
    protected void configure() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        bind(Key.get(MessageSender.class, Names.named("oxalis-as4")))
                .to(As4MessageSenderFacade.class);

        bind(Key.get(ExecutorService.class, Names.named("compression-pool")))
                .toProvider(() -> Executors.newFixedThreadPool(5)).in(Scopes.SINGLETON);

        bind(CompressionUtil.class);

        bind(MessagingProvider.class);

        bind(As4MessageSender.class);

        bind(TransmissionResponseConverter.class);
        bind(OutboundMerlinProvider.class);
    }

    @Provides
    @Singleton
    public Bus getBus() {
        Bus bus = BusFactory.getDefaultBus(true);
        new OxalisAlgorithmSuiteLoader(bus);
        return bus;
    }

    @Provides
    @Singleton
    public PeppolConfiguration getPeppolOutboundConfiguration(Settings<As4Conf> settings) {

        if (CEF_CONNECTIVITY.equalsIgnoreCase(settings.getString(As4Conf.TYPE))) {
            return new PeppolConfiguration() {

                @Override
                public String getPartyIDType() {
                    return "urn:oasis:names:tc:ebcore:partyid-type:unregistered";
                }

                @Override
                public String getAgreementRef() {
                    return null;
                }
            };
        }

        return new PeppolConfiguration();
    }

    @Provides
    @Singleton
    public ActionProvider getActionProvider(Settings<As4Conf> settings) {
        if (CEF_CONNECTIVITY.equalsIgnoreCase(settings.getString(As4Conf.TYPE))) {
            return p -> {
                String action = TransmissionRequestUtil.translateDocumentTypeToAction(p);

                if (action.startsWith("connectivity::cef##connectivity::")) {
                    return action.replaceFirst("connectivity::cef##connectivity::", "");
                }

                return action;
            };
        }

        return new DefaultActionProvider();
    }
}
