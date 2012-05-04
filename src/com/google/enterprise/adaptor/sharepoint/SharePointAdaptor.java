// Copyright 2012 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.sharepoint;

import com.google.common.collect.Lists;
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdEncoder;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;

import org.apache.axiom.om.OMContainer;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axis2.AxisFault;
import org.apache.axis2.client.Options;
import org.apache.axis2.databinding.types.UnsignedInt;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.HttpTransportProperties;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.NTCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

/**
 * SharePoint Adaptor for the GSA.
 */
public class SharePointAdaptor extends AbstractAdaptor {
  private static final Charset CHARSET = Charset.forName("UTF-8");
  private static final QName DATA_ELEMENT
      = new QName("urn:schemas-microsoft-com:rowset", "data");
  private static final QName ROW_ELEMENT = new QName("#RowsetSchema", "row");
  private static final QName OWS_FSOBJTYPE_ATTRIBUTE
      = new QName("ows_FSObjType");
  private static final QName OWS_TITLE_ATTRIBUTE = new QName("ows_Title");
  private static final QName OWS_SERVERURL_ATTRIBUTE
      = new QName("ows_ServerUrl");
  private static final QName OWS_CONTENTTYPE_ATTRIBUTE
      = new QName("ows_ContentType");

  private final ConcurrentMap<String, SiteDataClient> clients
      = new ConcurrentSkipListMap<String, SiteDataClient>();
  private final XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
  private final DocId virtualServerDocId = new DocId("");
  private final HttpClient httpClient = new HttpClient();
  private AdaptorContext context;
  private String virtualServer;

  @Override
  public void initConfig(Config config) {
    config.addKey("sharepoint.server", null);
    config.addKey("sharepoint.username", null);
    config.addKey("sharepoint.password", null);
    config.addKey("sharepoint.domain", "");
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    this.context = context;
    Config config = context.getConfig();
    virtualServer = config.getValue("sharepoint.server");
    String username = config.getValue("sharepoint.username");
    String password = config.getValue("sharepoint.password");
    String domain = config.getValue("sharepoint.domain");

    Credentials creds = new NTCredentials(username, password,
        config.getServerHostname(), domain);
    httpClient.getState().setCredentials(AuthScope.ANY, creds);
  }

  @Override
  public void getDocContent(Request request, Response response)
      throws IOException {
    try {
      DocId id = request.getDocId();
      SiteDataClient virtualServerClient = getSiteDataClient(virtualServer);
      if (id.equals(virtualServerDocId)) {
        virtualServerClient.getVirtualServerDocContent(request, response);
      } else {
        SiteDataClient client
            = virtualServerClient.getClientForUrl(id.getUniqueId());
        if (client == null) {
          response.respondNotFound();
          return;
        }
        client.getDocContent(request, response);
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException {
    pusher.pushDocIds(Arrays.asList(virtualServerDocId));
  }

  private SiteDataClient getSiteDataClient(String site) throws AxisFault {
    SiteDataClient client = clients.get(site);
    if (client == null) {
      client = new SiteDataClient(site);
      clients.putIfAbsent(site, client);
      client = clients.get(site);
    }
    return client;
  }

  public static void main(String[] args) throws Exception {
    if (false) {
      final String logPackage = "org.apache.commons.logging.";
      System.setProperty(logPackage + "Log", logPackage + "impl.SimpleLog");
      System.setProperty(logPackage + "simplelog.log.httpclient.wire", "debug");
      System.setProperty(
          logPackage + "simplelog.log.org.apache.commons.httpclient", "debug");
    }
    AbstractAdaptor.main(new SharePointAdaptor(), args);
  }

  private class SiteDataClient {
    private static final boolean DEBUG = false;
    private static final String XMLNS
        = "http://schemas.microsoft.com/sharepoint/soap/";

    private final SiteDataStub stub;
    private final String siteUrl;

    public SiteDataClient(String site) throws AxisFault {
      if (!site.endsWith("/")) {
        // Always end with a '/' for a cannonical form.
        site = site + "/";
      }
      this.siteUrl = site;
      this.stub = new SiteDataStub(site + "_vti_bin/SiteData.asmx");
      Options options = stub._getServiceClient().getOptions();
      options.setProperty(HTTPConstants.CACHED_HTTP_CLIENT, httpClient);
    }

    private void getDocContent(Request request, Response response)
        throws Exception {
      SiteDataStub.GetURLSegments urlRequest
          = new SiteDataStub.GetURLSegments();
      urlRequest.setStrURL(request.getDocId().getUniqueId());
      SiteDataStub.GetURLSegmentsResponse urlResponse
          = stub.getURLSegments(urlRequest);
      if (DEBUG) {
        System.out.println("getURLSegmentsResponse");
        System.out.println("Result: "
            + urlResponse.getGetURLSegmentsResult());
        System.out.println("webid: " + urlResponse.getStrWebID());
        System.out.println("itemid: " + urlResponse.getStrItemID());
        System.out.println("listid: " + urlResponse.getStrListID());
        System.out.println("bucketid: " + urlResponse.getStrBucketID());
      }
      if (!urlResponse.getGetURLSegmentsResult()) {
        response.respondNotFound();
        return;
      }
      if (urlResponse.getStrWebID() != null) {
        getSiteDocContent(request, response, urlResponse.getStrWebID());
      } else if (urlResponse.getStrItemID() != null) {
        getListItemDocContent(request, response, urlResponse.getStrListID(),
            urlResponse.getStrItemID());
      } else if (urlResponse.getStrListID() != null) {
        getListDocContent(request, response, urlResponse.getStrListID());
      } else if (urlResponse.getStrBucketID() != null) {
        response.respondNotFound();
      } else {
        // Assume it is a top-level site.
        getSiteDocContent(request, response, urlResponse.getStrWebID());
      }
    }

    public void callChangesContentDatabase() throws Exception {
      System.out.println("                            ContentDatabase Changes");
      SiteDataStub.GetChanges request = new SiteDataStub.GetChanges();
      request.setObjectType(SiteDataStub.ObjectType.ContentDatabase);
      request.setContentDatabaseId("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}");
      request.setLastChangeId(
          "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634704678460030000;261");
      //request.setCurrentChangeId("");
      request.setTimeout(2);
      SiteDataStub.GetChangesResponse response = stub.getChanges(request);
      System.out.println(response.getMoreChanges());
      System.out.println(response.getCurrentChangeId());
      System.out.println(response.getLastChangeId());
      System.out.println(response.getGetChangesResult());
    }

    private DocId encodeDocId(String url) {
      if (url.toLowerCase().startsWith("https://")
          || url.toLowerCase().startsWith("http://")) {
        // Leave as-is.
      } else if (!url.startsWith("/")) {
        url = siteUrl + url;
      } else {
        // Rip off everthing after the third slash (including the slash).
        // Get http://example.com from http://example.com/some/folder.
        String[] parts = siteUrl.split("/", 4);
        url = parts[0] + "//" + parts[2] + url;
      }
      return new DocId(url);
    }

    private String encodeUrl(String url) {
      URI uri = context.getDocIdEncoder().encodeDocId(encodeDocId(url));
      return uri.toASCIIString();
    }

    private String liUrl(String url) {
      // TODO(ejona): Fix raw string concatenation.
      return "<li><a href=\"" + encodeUrl(url) + "\">" + url + "</a></li>";
    }

    private void getVirtualServerDocContent(Request request, Response response)
        throws Exception {
      SiteDataStub.VirtualServer vs = getContentVirtualServer();
      response.setContentType("text/html");
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      writer.write("<!DOCTYPE html>\n"
          + "<html><head>"
          + "<title>VirtualServer " + vs.getMetadata().getURL() + "</title>"
          + "</head>"
          + "<body>"
          + "<h1>VirtualServer " + vs.getMetadata().getURL() + "</h1>"
          + "<p>Sites</p>"
          + "<ul>");
      if (vs.getContentDatabases() != null) {
        DocIdEncoder encoder = context.getDocIdEncoder();
        for (SiteDataStub.ContentDatabase_type0 cd_t0
            : vs.getContentDatabases().getContentDatabase()) {
          SiteDataStub.ContentDatabase cd
            = getContentContentDatabase(cd_t0.getID());
          if (cd.getSites() != null && cd.getSites().getSite() != null) {
            for (SiteDataStub.Site_type0 site : cd.getSites().getSite()) {
              writer.write(liUrl(site.getURL()));
            }
          }
        }
      }
      writer.write("</ul></body></html>");
      writer.flush();
    }

    private void getSiteDocContent(Request request, Response response,
        String id) throws Exception {
      SiteDataStub.Web w = getContentWeb(id);
      response.setContentType("text/html");
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      writer.write("<!DOCTYPE html>\n"
          + "<html><head>"
          + "<title>Site " + w.getMetadata().getTitle() + "</title>"
          + "</head>"
          + "<body>"
          + "<h1>Site " + w.getMetadata().getTitle() + "</h1>");

      // TODO: w.getMetadata().getNoIndex()
      DocIdEncoder encoder = context.getDocIdEncoder();
      if (w.getWebs() != null && w.getWebs().getWeb() != null) {
        writer.write("<p>Sites</p><ul>");
        for (SiteDataStub.Web_type0 web : w.getWebs().getWeb()) {
          writer.write(liUrl(web.getURL()));
        }
        writer.write("</ul>");
      }
      if (w.getLists() != null && w.getLists().getList() != null) {
        writer.write("<p>Lists</p><ul>");
        for (SiteDataStub.List_type0 list : w.getLists().getList()) {
          writer.write(liUrl(list.getDefaultViewUrl()));
        }
        writer.write("</ul>");
      }
      if (w.getFPFolder() != null) {
        SiteDataStub.FolderData f = w.getFPFolder();
        if (f.getFolders() != null) {
          writer.write("<p>Folders</p><ul>");
          for (SiteDataStub.Folders folders : f.getFolders()) {
            if (folders.getFolder() != null) {
              for (SiteDataStub.Folder_type0 folder : folders.getFolder()) {
                writer.write(liUrl(folder.getURL()));
              }
            }
          }
          writer.write("</ul>");
        }
        if (f.getFiles() != null) {
          writer.write("<p>Files</p><ul>");
          for (SiteDataStub.Files files : f.getFiles()) {
            if (files.getFile() != null) {
              for (SiteDataStub.File_type0 file : files.getFile()) {
                writer.write(liUrl(file.getURL()));
              }
            }
          }
          writer.write("</ul>");
        }
      }
      writer.write("</body></html>");
      writer.flush();
    }

    private void getListDocContent(Request request, Response response,
        String id) throws Exception {
      SiteDataStub.List l = getContentList(id);
      processFolder(id, "", response);
    }

    private void processFolder(String listGuid, String folderPath,
        Response response) throws Exception {
      response.setContentType("text/html");
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      writer.write("<!DOCTYPE html>\n"
          + "<html><head>"
          + "<title>Folder " + folderPath + "</title>"
          + "</head>"
          + "<body>"
          + "<h1>Folder " + folderPath + "</h1>");
      SiteDataStub.Folder folder = getContentFolder(listGuid, folderPath);
      SiteDataStub.Xml xml = folder.getFolder().getXml();

      OMElement data = getFirstChildWithName(xml, DATA_ELEMENT);
      writer.write("<p>List items</p><ul>");
      for (OMElement row : getChildrenWithName(data, ROW_ELEMENT)) {
        String rowUrl = row.getAttributeValue(OWS_SERVERURL_ATTRIBUTE);
        String rowTitle = row.getAttributeValue(OWS_TITLE_ATTRIBUTE);
        // TODO(ejona): Fix raw string concatenation.
        writer.write("<li><a href=\"" + encodeUrl(rowUrl) + "\">" + rowTitle
            + "</a></li>");
      }
      writer.write("</ul>");

      writer.write("</body></html>");
      writer.flush();
    }

    private OMElement getFirstChildWithName(SiteDataStub.Xml xml, QName name) {
      for (OMElement child : xml.getExtraElement()) {
        if (child.getQName().equals(name)) {
          return child;
        }
      }
      return null;
    }

    private List<OMElement> getChildrenWithName(OMElement ele, QName name) {
      @SuppressWarnings("unchecked")
      Iterator<OMElement> children = ele.getChildrenWithName(ROW_ELEMENT);
      return Lists.newArrayList(children);
    }

    private void getListItemDocContent(Request request, Response response,
        String listId, String itemId) throws Exception {
      SiteDataStub.ItemData i = getContentItem(listId, itemId);
      SiteDataStub.Xml xml = i.getXml();

      OMElement data = getFirstChildWithName(xml, DATA_ELEMENT);
      OMElement row = getChildrenWithName(data, ROW_ELEMENT).get(0);
      // This should be in the form of "1234;#0". We want to extract the 0.
      String type =
          row.getAttributeValue(OWS_FSOBJTYPE_ATTRIBUTE).split(";#", 2)[1];
      boolean isFolder = "1".equals(type);
      String title = row.getAttributeValue(OWS_TITLE_ATTRIBUTE);
      if (title == null) {
        title = "Unknown title";
      }
      String serverUrl = row.getAttributeValue(OWS_SERVERURL_ATTRIBUTE);

      if (isFolder) {
        SiteDataStub.List l = getContentList(listId);
        String root
            = encodeDocId(l.getMetadata().getRootFolder()).getUniqueId();
        System.out.println("serverUrl: " + serverUrl);
        String folder = encodeDocId(serverUrl).getUniqueId();
        if (!folder.startsWith(root)) {
          throw new AssertionError();
        }
        System.out.println("folder: " + folder);
        processFolder(listId, folder.substring(root.length()), response);
        return;
      }
      String contentType = row.getAttributeValue(OWS_CONTENTTYPE_ATTRIBUTE);
      // TODO(ejona): This is likely unreliable. Investigate a better way.
      if ("Document".equals(contentType)) {
        // This is a file, so display its contents.
        String url = request.getDocId().getUniqueId();
        String[] parts = url.split("/", 4);
        url = parts[0] + "/" + parts[1] + "/" + parts[2] + "/" +
            new URI(null, null, parts[3], null).toASCIIString();
        GetMethod method = new GetMethod(url);
        int statusCode = httpClient.executeMethod(method);
        if (statusCode != HttpStatus.SC_OK) {
          throw new RuntimeException("Got status code: " + statusCode);
        }
        InputStream is = method.getResponseBodyAsStream();
        IOHelper.copyStream(is, response.getOutputStream());
      } else {
        // Some list item.
        Writer writer
            = new OutputStreamWriter(response.getOutputStream(), CHARSET);
        // TODO(ejona): Handle this case.
        writer.write("TODO: ListItem");
      }
    }

    private SiteDataStub.VirtualServer getContentVirtualServer()
        throws Exception {
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.VirtualServer);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      if (DEBUG) {
        System.out.println("                            VirtualServer");
        System.out.println(response.getLastItemIdOnPage());
        System.out.println(response.getGetContentResult());
      }
      String xml = response.getGetContentResult();
      xml = xml.replace("<VirtualServer>",
          "<VirtualServer xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(
          new StringReader(xml));
      return SiteDataStub.VirtualServer.Factory.parse(reader);
    }

    private SiteDataClient getClientForUrl(String url) throws Exception {
      SiteDataStub.GetSiteAndWeb request = new SiteDataStub.GetSiteAndWeb();
      request.setStrUrl(url);
      SiteDataStub.GetSiteAndWebResponse response = stub.getSiteAndWeb(request);
      if (DEBUG) {
        System.out.println("GetSiteAndWeb");
        System.out.println("Result: " + response.getGetSiteAndWebResult());
        System.out.println("Site: " + response.getStrSite());
        System.out.println("Web: " + response.getStrWeb());
      }
      if (response.getGetSiteAndWebResult().longValue() != 0) {
        return null;
      }
      return getSiteDataClient(response.getStrWeb());
    }

    private SiteDataStub.ContentDatabase getContentContentDatabase(String id)
        throws Exception {
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.ContentDatabase);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setObjectId(id);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      if (DEBUG) {
        System.out.println("                            ContentDatabase");
        System.out.println(response.getLastItemIdOnPage());
        System.out.println(response.getGetContentResult());
      }
      String xml = response.getGetContentResult();
      xml = xml.replace("<ContentDatabase>",
          "<ContentDatabase xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(
          new StringReader(xml));
      return SiteDataStub.ContentDatabase.Factory.parse(reader);
    }

    private SiteDataStub.Web getContentWeb(String id) throws Exception {
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.Site);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setObjectId(id);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      if (DEBUG) {
        System.out.println("                            Web");
        System.out.println(id);
        System.out.println(response.getLastItemIdOnPage());
        System.out.println(response.getGetContentResult());
      }
      stub.getContent(request);
      String xml = response.getGetContentResult();
      xml = xml.replace("<Web>", "<Web xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(
          new StringReader(xml));
      return SiteDataStub.Web.Factory.parse(reader);
    }

    private SiteDataStub.List getContentList(String id) throws Exception {
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.List);
      request.setRetrieveChildItems(false);
      request.setSecurityOnly(false);
      request.setObjectId(id);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      if (DEBUG) {
        System.out.println("                            List");
        System.out.println(response.getLastItemIdOnPage());
        System.out.println(response.getGetContentResult());
      }
      String xml = response.getGetContentResult();
      xml = xml.replace("<List>", "<List xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(
          new StringReader(xml));
      return SiteDataStub.List.Factory.parse(reader);
    }

    private SiteDataStub.ItemData getContentItem(String listId, String itemId)
        throws Exception {
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.ListItem);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setObjectId(listId);
      request.setFolderUrl("");
      request.setItemId(itemId);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      if (DEBUG) {
        System.out.println("                            ListItem");
        System.out.println(response.getLastItemIdOnPage());
        System.out.println(response.getGetContentResult());
      }
      String xml = response.getGetContentResult();
      xml = xml.replace("<Item>", "<ItemData xmlns='" + XMLNS + "'>");
      xml = xml.replace("</Item>", "</ItemData>");
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(
          new StringReader(xml));
      return SiteDataStub.ItemData.Factory.parse(reader);
    }

    private SiteDataStub.Folder getContentFolder(String guid, String url)
        throws Exception {
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.Folder);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setFolderUrl(url);
      request.setObjectId(guid);
      request.setLastItemIdOnPage("");
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      if (DEBUG) {
        System.out.println("                            Folder (Specific)");
        System.out.println(response.getLastItemIdOnPage());
        System.out.println(response.getGetContentResult());
      }
      String xml = response.getGetContentResult();
      xml = xml.replace("<Folder>", "<Folder xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(
          new StringReader(xml));
      return SiteDataStub.Folder.Factory.parse(reader);
    }
  }
}
