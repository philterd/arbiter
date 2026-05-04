package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.repository.GeneralSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

@Service
public class GeneralSettingsService {

    private static final Logger log = LoggerFactory.getLogger(GeneralSettingsService.class);

    private final GeneralSettingsRepository repository;
    private final int serverPort;

    public GeneralSettingsService(GeneralSettingsRepository repository,
                                  @Value("${server.port:8080}") int serverPort) {
        this.repository = repository;
        this.serverPort = serverPort;
    }

    public GeneralSettings load() {
        GeneralSettings settings = repository.findById(GeneralSettings.SINGLETON_ID).orElse(null);
        if (settings == null) {
            settings = new GeneralSettings();
        }
        if (settings.getArbiterUrl() == null || settings.getArbiterUrl().isBlank()) {
            settings.setArbiterUrl(defaultArbiterUrl());
        }
        if (settings.getTimezone() == null || settings.getTimezone().isBlank()) {
            settings.setTimezone("UTC");
        }
        return settings;
    }

    public GeneralSettings save(GeneralSettings settings) {
        settings.setId(GeneralSettings.SINGLETON_ID);
        return repository.save(settings);
    }

    private String defaultArbiterUrl() {
        return "http://" + detectLocalAddress() + ":" + serverPort;
    }

    private static String detectLocalAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("Could not enumerate network interfaces: {}", e.getMessage());
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
