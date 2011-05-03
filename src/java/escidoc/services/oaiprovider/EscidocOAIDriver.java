package escidoc.services.oaiprovider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import proai.MetadataFormat;
import proai.SetInfo;
import proai.cache.MetadataValidator;
import proai.cache.ValidationInfo;
import proai.cache.ValidationResult;
import proai.driver.EscidocAdaptedOAIDriver;
import proai.driver.RemoteIterator;
import proai.driver.impl.RemoteIteratorImpl;
import proai.error.RepositoryException;

/**
 * Implementation of the OAIDriver interface for Fedora.
 * 
 * @author Edwin Shin, cwilper@cs.cornell.edu
 */

public class EscidocOAIDriver implements EscidocAdaptedOAIDriver {

    // private static final String _DC_SCHEMALOCATION =
    // "xsi:schemaLocation=\"http://www.openarchives.org/OAI/2.0/oai_dc/ "
    // + "http://www.openarchives.org/OAI/2.0/oai_dc.xsd\"";

    private static final String _DC_SCHEMALOCATION =
        "http://www.openarchives.org/OAI/2.0/oai_dc.xsd";

    private static final String _DC_NAMESPACEURI =
        "http://www.openarchives.org/OAI/2.0/oai_dc/";

    private static final String _XSI_DECLARATION =
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";

    private static final Pattern PATTERN__XSI_DECLARATION =
        Pattern.compile(
            ".*xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\".*",
            Pattern.DOTALL);

    private static final Logger logger =
        Logger.getLogger(EscidocOAIDriver.class.getName());

    public static final String NS = "driver.escidoc.";

    public static final String PROP_BASEURL = NS + "baseURL";

    public static final String PROP_SEARCH_BASEURL = NS + "search.baseURL";

    public static final String PROP_FORMATS = NS + "md.formats";

    public static final String PROP_NAMESPACE_IDENTIFIER =
        NS + "namespace-identifier";

    public static final String PROP_FORMAT_START = NS + "md.format.";

    public static final String PROP_DELETED = NS + "deleted";

    public static final String PROP_FORMAT_PFX_END = ".mdPrefix";

    public static final String PROP_FORMAT_LOC_END = ".loc";

    public static final String PROP_FORMAT_URI_END = ".uri";

    public static final String PROP_FORMAT_DISSTYPE_END = ".dissType";

    private EscidocQueryFactory m_queryFactory;

    private String m_escidocBaseURL;

    private String m_escidocSearchBaseURL;

    private String m_namespace_identifier;

    private Map<String, EscidocMetadataFormat> m_metadataFormats;

    private MetadataValidator _validator;

    public EscidocOAIDriver() {
    }

    // ////////////////////////////////////////////////////////////////////////
    // /////////////////// Methods from proai.driver.OAIDriver ////////////////
    // ////////////////////////////////////////////////////////////////////////

    public void init(Properties props) throws RepositoryException {

        m_escidocBaseURL = getRequired(props, PROP_BASEURL);
        m_namespace_identifier = getRequired(props, PROP_NAMESPACE_IDENTIFIER);
        if (!m_escidocBaseURL.endsWith("/"))
            m_escidocBaseURL += "/";
        m_escidocSearchBaseURL = getRequired(props, PROP_SEARCH_BASEURL);
        if (!m_escidocSearchBaseURL.endsWith("/"))
            m_escidocSearchBaseURL += "/";
        m_metadataFormats = getMetadataFormats(props);
        m_queryFactory = new EscidocQueryFactory();
        m_queryFactory.init(m_escidocBaseURL, m_escidocSearchBaseURL,
            m_namespace_identifier);
    }

    public void write(PrintWriter out) throws RepositoryException {
        String identity = m_queryFactory.retrieveIndentity();
        out.print(identity);
    }

    // TODO: date for volatile disseminations?
    public Date getLatestDate() throws RepositoryException {
        return m_queryFactory.latestRecordDate();
    }

    public RemoteIterator<? extends MetadataFormat> listMetadataFormats()
        throws RepositoryException {
        return new RemoteIteratorImpl<EscidocMetadataFormat>(m_metadataFormats
            .values().iterator());
    }

    public RemoteIterator<SetInfo> listSetInfo() throws RepositoryException {

        return m_queryFactory.listSetInfo();
    }

    public RemoteIterator<EscidocRecord> listRecords(
        Date from, Date until, String mdPrefix, Set<String> newSetSpecs)
        throws RepositoryException {

        if (from != null && until != null && from.after(until)) {
            throw new RepositoryException(
                "from date cannot be later than until date.");
        }

        return m_queryFactory.listRecords(from, until, m_metadataFormats
            .get(mdPrefix), newSetSpecs);
    }

    public ValidationInfo writeRecordXML(
        String itemID, String mdPrefix, String sourceInfo, PrintWriter out)
        throws RepositoryException {
        ValidationInfo validationInfo = null;
        // Parse the sourceInfo string
        String[] parts = sourceInfo.trim().split(" ");
        if (parts.length < 6) {
            throw new RepositoryException(
                "Error parsing sourceInfo (expecting " + "6 or more parts): '"
                    + sourceInfo + "'");
        }
        String resourceId = parts[0];
        String dissURI = parts[1];
        boolean deleted = parts[2].equalsIgnoreCase("true");
        String date = parts[3];
        String releaseDate = parts[4];
        String resourceType = parts[5];
        // List<String> setSpecs = new ArrayList<String>();
        // for (int i = 6; i < parts.length; i++) {
        // setSpecs.add(parts[i]);
        // }
        out.println("<record xmlns=\"http://www.openarchives.org/OAI/2.0/\">");
        if (deleted) {
            writeRecordHeader(itemID, deleted, date, out);
        }
        else {
            writeRecordHeader(itemID, deleted, releaseDate, out);
        }
        if (!deleted) {
            validationInfo =
                writeRecordMetadata(resourceId, dissURI, resourceType,
                    mdPrefix, out);

        }
        else {
            logger
                .info("Record was marked deleted: " + itemID + "/" + mdPrefix);
        }
        out.println("</record>");
        return validationInfo;
    }

    private static void writeRecordHeader(
        String itemID, boolean deleted, String date, PrintWriter out) {
        if (deleted) {
            out.println("  <header status=\"deleted\">");
        }
        else {
            out.println("  <header>");
        }
        out.println("    <identifier>" + itemID + "</identifier>");
        DateTime dateTime = new DateTime(date);
        DateTime newDate = dateTime.withZone(DateTimeZone.UTC);
        String dateString = newDate.toString("yyyy-MM-dd'T'HH:mm:ss'Z'");
        out.println("    <datestamp>" + dateString + "</datestamp>");
        // No need to write set specs now, set specs will be overwritten while
        // printing
        // of the response
        // for (int i = 0; i < setSpecs.size(); i++) {
        // out.println("    <setSpec>" + (String) setSpecs.get(i)
        // + "</setSpec>");
        // }
        out.println("  </header>");
    }

    private ValidationInfo writeRecordMetadata(
        String resourceId, String dissURI, String resourceType,
        String mdPrefix, PrintWriter out) throws RepositoryException {
        ValidationInfo validationInfo = new ValidationInfo();
        GetMethod getWithMdRecordContent = null;
        if (dissURI.equals("DC")) {
            getWithMdRecordContent =
                EscidocConnector.requestRetrieveDc(resourceId, resourceType);
        }
        else if (dissURI.startsWith("resources")) {
            getWithMdRecordContent =
                EscidocConnector.requestRetrieveResource(resourceId,
                    resourceType, dissURI);
        }
        else {
            getWithMdRecordContent =
                EscidocConnector.requestRetrieveMdRecord(resourceId,
                    resourceType, dissURI);
        }
        InputStream in = null;
        BufferedReader reader = null;
        try {
            in = getWithMdRecordContent.getResponseBodyAsStream();
            if (in == null) {
                throw new RepositoryException(
                    "Body content of a GET-request is null " + resourceId
                        + " md-prefix: " + mdPrefix);
            }
            // FIXME use xml reader for reading xml, charset of HTTP response
            // might not be charset of XML document (https://www.escidoc.org/jira/browse/INFR-930)
            String charset = getWithMdRecordContent.getResponseCharSet();
            reader = new BufferedReader(new InputStreamReader(in, charset));
            StringBuffer buf = new StringBuffer();
            String line = reader.readLine();
            while (line != null) {
                buf.append(line + "\n");
                line = reader.readLine();
            }
            String xml = buf.toString();
            validationInfo = _validator.validate(mdPrefix, xml);
            if (validationInfo.getResult().equals(ValidationResult.invalid)) {
                return validationInfo;
            }
            xml = xml.replaceAll("\\s*<\\?xml.*?\\?>\\s*", "");
            // if (dissURI.endsWith("/DC")) {
            // // If it's a DC datastream dissemination, inject the
            // // xsi:schemaLocation attribute
            // xml =
            // xml.replaceAll("<oai_dc:dc ", "<oai_dc:dc "
            // + _XSI_DECLARATION + " " + _DC_SCHEMALOCATION + " ");
            // }

            // Ask OAI-PMH developer if reasonable:
            // put a mandatory attribute xmlns:xsi (according to the OAI_PMH
            // spec)
            // into a root element of a meta data, if it is missing
            // if (!PATTERN__XSI_DECLARATION.matcher(xml).matches()) {
            // xml = xml.replaceFirst("xmlns", _XSI_DECLARATION + " xmlns" );
            // }
            out.println("  <metadata>");
            out.print(xml);
            out.println("  </metadata>");
            return validationInfo;
        }
        catch (IOException e) {
            throw new RepositoryException("IO error reading " + dissURI, e);
        }
        finally {
            if (reader != null)
                try {
                    getWithMdRecordContent.releaseConnection();
                    reader.close();
                }
                catch (IOException e) {
                }
        }
    }

    private HashMap<String, Vector<String>> searchHitLists =
        new HashMap<String, Vector<String>>();

    private HashMap<String, SetInfo> setDefinitions =
        new HashMap<String, SetInfo>();

    public void close() throws RepositoryException {
        // TODO Auto-generated method stub

    }

    // ////////////////////////////////////////////////////////////////////////
    // //////////////////////////// Helper Methods ////////////////////////////
    // ////////////////////////////////////////////////////////////////////////

    /**
     * @param props
     */
    private Map<String, EscidocMetadataFormat> getMetadataFormats(
        Properties props) throws RepositoryException {
        String formats[], prefix, namespaceURI, schemaLocation;
        EscidocMetadataFormat mf;
        Map<String, EscidocMetadataFormat> map =
            new HashMap<String, EscidocMetadataFormat>();

        // step through formats, getting appropriate properties for each
        formats = getRequired(props, PROP_FORMATS).split(" ");
        for (int i = 0; i < formats.length; i++) {
            prefix = formats[i];
            namespaceURI =
                getRequired(props, PROP_FORMAT_START + prefix
                    + PROP_FORMAT_URI_END);
            schemaLocation =
                getRequired(props, PROP_FORMAT_START + prefix
                    + PROP_FORMAT_LOC_END);

            String otherPrefix =
                props.getProperty(PROP_FORMAT_START + prefix
                    + PROP_FORMAT_PFX_END);
            if (otherPrefix != null)
                prefix = otherPrefix;

            String mdDissType =
                PROP_FORMAT_START + prefix + PROP_FORMAT_DISSTYPE_END;

            if (prefix.equals("oai_dc")) {
                namespaceURI = _DC_NAMESPACEURI;
                schemaLocation = _DC_SCHEMALOCATION;
            }
            mf =
                new EscidocMetadataFormat(prefix, namespaceURI, schemaLocation,
                    getRequired(props, mdDissType));
            map.put(prefix, mf);
        }
        if (!map.containsKey("oai_dc")) {
            logger
                .warn("oai_dc format is missing in the configuration file. Specifed oai_dc format"
                    + " with a dissemination type 'DC'");
            EscidocMetadataFormat dcFormat =
                new EscidocMetadataFormat("oai_dc", _DC_NAMESPACEURI,
                    _DC_SCHEMALOCATION, "DC");
            map.put("oai_dc", dcFormat);
        }
        return map;
    }

    protected static String getRequired(Properties props, String key)
        throws RepositoryException {
        String val = props.getProperty(key);
        if (val == null) {
            throw new RepositoryException("Required property is not set: "
                + key);
        }
        logger.debug("Required property: " + key + " = " + val);
        return val.trim();
    }

    protected static int getRequiredInt(Properties props, String key)
        throws RepositoryException {
        String val = getRequired(props, key);
        try {
            return Integer.parseInt(val);
        }
        catch (Exception e) {
            throw new RepositoryException("Value of property " + key
                + " is not an integer: " + val);
        }
    }

    /**
     * @param props
     * @param key
     * @return the value associated with key or the empty String ("")
     */
    protected static String getOptional(Properties props, String key) {
        String val = props.getProperty(key);
        logger.debug(key + " = " + val);
        if (val == null) {
            return "";
        }
        return val.trim();
    }

    public HashMap<String, SetInfo> retrieveUserDefinedSetList(
        boolean updateStart) {
        return m_queryFactory.retrieveUserDefinedSetList(updateStart);

    }

    public Vector<String> retrieveIdsForSetQuery(String setSpecification) {
        return m_queryFactory.retrieveIdsForSetQuery(setSpecification);
    }

    public void setValidator(MetadataValidator validator) {
        _validator = validator;
    }
}
