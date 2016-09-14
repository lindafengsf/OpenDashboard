/**
 * 
 */
package od.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import java.util.Map;
import java.util.UUID;

import od.providers.ProviderException;
import od.providers.course.CourseProvider;
import od.providers.course.learninglocker.LearningLockerStaff;
import od.repository.mongo.MongoTenantRepository;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author ggilbert
 *
 */
@Component
public class OpenDashboardAuthenticationProvider implements AuthenticationProvider {

  final static Logger log = LoggerFactory.getLogger(OpenDashboardAuthenticationProvider.class);
  
  @Value("${ll.use.demo:false}")
  protected boolean DEMO = false;
  
  @Value("${ukfederation.role:staff}")
  private String validRole;
  
  @Autowired private CourseProvider courseProvider;
  @Autowired private MongoTenantRepository mongoTenantRepository;

  @Value("${od.admin.user:admin}")
  private String adminUsername;
  
  @Value("${od.admin.password:admin}")
  private String adminPassword;
  
  @Value("${jwt.key:xasdsadmdscasd!!!}")
  private String jwtKey;

  @Override
  public boolean supports(Class<?> authentication) {
    log.debug("{}",OpenDashboardAuthenticationToken.class.isAssignableFrom(authentication));
    return (OpenDashboardAuthenticationToken.class.isAssignableFrom(authentication) || UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication));
  }

  @Override
  public Authentication authenticate(Authentication token) throws AuthenticationException {
    
    log.debug("{}", token);
    OpenDashboardAuthenticationToken authToken = null;
    String uuid = UUID.randomUUID().toString();
    
    if (token instanceof OpenDashboardAuthenticationToken) {
      OpenDashboardAuthenticationToken odToken = (OpenDashboardAuthenticationToken)token;
      
      if (odToken.getLaunchRequest() != null) {
        log.debug("is lti");
        
        authToken = new OpenDashboardAuthenticationToken(odToken.getLaunchRequest(), 
            null,
            odToken.getTenantId(), 
            new OpenDashboardUser(odToken.getLaunchRequest().getUser_id(), 
                uuid, 
                odToken.getAuthorities(), 
                odToken.getTenantId(), 
                odToken.getLaunchRequest()), 
            uuid,
            odToken.getAuthorities());
      }
      else {
        log.debug("is jwt");
        
        String jwtJson = odToken.getJwtToken();
        // parse out the token
        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = null;
        try {
          actualObj = mapper.readTree(jwtJson);
        } 
        catch (Exception e) {
          log.error(e.getMessage(),e);
          throw new AuthenticationServiceException(e.getMessage());
        } 
        log.info("{}", actualObj.get("jwt").textValue());
        
        // parse the token into claims
        Jws<Claims> claims = null;
        try {
          claims = Jwts.parser().setSigningKey(jwtKey.getBytes("UTF-8")).parseClaimsJws(actualObj.get("jwt").textValue());
        } 
        catch (Exception e) {
          log.error(e.getMessage(),e);
          throw new AuthenticationServiceException(e.getMessage());
        } 
        
        log.info("data: {}", claims.getBody().get("data"));
        Map<String, String> data = (Map<String, String>)claims.getBody().get("data");
        log.info("data map: {}", data);
        String pid = data.get("eppn");
        log.info("pid: {}", pid);
        String affiliation = data.get("affiliation");
        log.info("affiliation: {}",affiliation);
        
        if (StringUtils.isBlank(affiliation) || !StringUtils.contains(affiliation, validRole)) {
          throw new InsufficientAuthenticationException(String.format("Invalid affiliation: {}",affiliation));
        }
        
        try {
          LearningLockerStaff staff = courseProvider.getStaffWithPid(mongoTenantRepository.findOne(odToken.getTenantId()), pid);
          authToken = new OpenDashboardAuthenticationToken(null, 
              null,
              odToken.getTenantId(), 
              new OpenDashboardUser(staff.getStaffId(), 
                  uuid, 
                  AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_INSTRUCTOR"), 
                  odToken.getTenantId(), 
                  null), 
              uuid,
              AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_INSTRUCTOR"));

        } 
        catch (ProviderException e) {
          log.error(e.getMessage(),e);
          throw new AuthenticationCredentialsNotFoundException(e.getMessage(), e);
        }

      }
    }
    else if (token instanceof UsernamePasswordAuthenticationToken) {
      log.debug("not opendashboardauthenticationtoken");
      UsernamePasswordAuthenticationToken upToken = (UsernamePasswordAuthenticationToken)token;
      
      if (upToken.getPrincipal().equals(adminUsername) && 
          upToken.getCredentials().equals(adminPassword)) {
        authToken = new OpenDashboardAuthenticationToken(null, 
            null,
            null, 
            new OpenDashboardUser(adminUsername, 
                uuid, 
                AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_INSTRUCTOR,ROLE_ADMIN"), 
                null, 
                null), 
            uuid,
            AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_INSTRUCTOR,ROLE_ADMIN"));
      }
      
    }
    
    return authToken;
  }

}
