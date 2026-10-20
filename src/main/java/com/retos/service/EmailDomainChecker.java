package com.retos.service;

import java.util.Hashtable;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verificacion opcional (EMAIL_MX_CHECK=true) de que el dominio del correo puede recibir correo:
 * tiene registro MX, o A/AAAA (MX implicito). Falla de forma segura: si el DNS no responde
 * (sin red, timeout) el correo se acepta; solo se rechaza si el dominio no existe o no tiene registros.
 */
@Component
public class EmailDomainChecker {

    private static final Logger log = LoggerFactory.getLogger(EmailDomainChecker.class);

    private final boolean enabled;

    public EmailDomainChecker(@Value("${app.validation.email-mx-check:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean canReceiveMail(String email) {
        if (!enabled || email == null || !email.contains("@")) {
            return true;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1);
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[] {"MX", "A", "AAAA"});
            return attrs.size() > 0;
        } catch (NameNotFoundException e) {
            return false;
        } catch (NamingException e) {
            log.warn("No se pudo verificar el dominio {} por DNS; se acepta el correo ({})", domain, e.toString());
            return true;
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // nada que hacer
                }
            }
        }
    }
}
