package org.apereo.cas.ticket;

import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.ticket.proxy.ProxyGrantingTicket;
import org.apereo.cas.ticket.proxy.ProxyTicket;
import org.apereo.cas.ticket.tracking.TicketTrackingPolicy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.NoArgsConstructor;
import lombok.val;

import java.io.Serial;

/**
 * Concrete implementation of a proxy granting ticket (PGT). A PGT is
 * used by a service to obtain proxy tickets for obtaining access to a back-end
 * service on behalf of a client. It is analogous to a ticket-granting ticket
 * but only for proxying purposes. Proxy tickets will be issued off of
 * a given proxy granting ticket.
 * <p>
 * NOTE: A PGT shares the same implementation as a {@link TicketGrantingTicket}.
 * The intention of this implementation at this point is make explicit and visible
 * the use and declaration of a PGT per the CAS protocol.
 *
 * @author Misagh Moayyed
 * @since 4.1
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
@NoArgsConstructor
public class ProxyGrantingTicketImpl extends TicketGrantingTicketImpl implements ProxyGrantingTicket {

    @Serial
    private static final long serialVersionUID = -8126909926138945649L;

    /**
     * Instantiates a new proxy granting ticket impl.
     *
     * @param id             the id
     * @param authentication the authentication
     * @param policy         the policy
     */
    public ProxyGrantingTicketImpl(final String id, final Authentication authentication, final ExpirationPolicy policy) {
        super(id, authentication, policy);
    }

    /**
     * Instantiates a new proxy granting ticket impl.
     *
     * @param id                         the id
     * @param proxiedBy                  the proxied by
     * @param parentTicketGrantingTicket the ticket granting ticket
     * @param authentication             the authentication
     * @param expirationPolicy           the policy
     */
    @JsonCreator
    public ProxyGrantingTicketImpl(@JsonProperty("id") final String id, @JsonProperty("proxiedBy") final Service proxiedBy,
                                   @JsonProperty("ticketGrantingTicket") final TicketGrantingTicket parentTicketGrantingTicket,
                                   @JsonProperty("authentication") final Authentication authentication,
                                   @JsonProperty("expirationPolicy") final ExpirationPolicy expirationPolicy) {
        super(id, proxiedBy, parentTicketGrantingTicket, authentication, expirationPolicy);
    }

    @Override
    public ProxyTicket grantProxyTicket(final String id, final Service service,
                                        final ExpirationPolicy expirationPolicy,
                                        final TicketTrackingPolicy trackingPolicy) {
        val serviceTicket = new ProxyTicketImpl(id, this, service, false, expirationPolicy);
        trackingPolicy.trackTicket(this, serviceTicket);
        return serviceTicket;
    }

    @Override
    public String getPrefix() {
        return ProxyGrantingTicket.PROXY_GRANTING_TICKET_PREFIX;
    }
}
