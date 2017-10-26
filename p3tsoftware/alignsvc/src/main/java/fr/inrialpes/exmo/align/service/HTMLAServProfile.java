/*
 * $Id: HTMLAServProfile.java 1841 2013-03-24 17:28:33Z euzenat $
 *
 * Copyright (C) INRIA, 2006-2013
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package fr.inrialpes.exmo.align.service;

import fr.inrialpes.exmo.align.impl.Annotations;
import fr.inrialpes.exmo.align.impl.Namespace;
import fr.inrialpes.exmo.align.service.msg.Message;
import fr.inrialpes.exmo.align.service.msg.ErrorMsg;
import fr.inrialpes.exmo.align.service.msg.AlignmentId;
import fr.inrialpes.exmo.align.service.msg.AlignmentIds;
import fr.inrialpes.exmo.align.service.msg.EvaluationId;

import org.semanticweb.owl.align.Alignment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;

import java.util.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;

import java.lang.Integer;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.jetty.Handler;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.Request;
import org.mortbay.servlet.MultiPartFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTMLAServProfile: an HTML provile for the Alignment server
 * It embeds an HTTP server.
 */

public class HTMLAServProfile implements AlignmentServiceProfile {
    final static Logger logger = LoggerFactory.getLogger( HTMLAServProfile.class );

    private int tcpPort;
    private String tcpHost;
    private Server server;
    private AServProtocolManager manager;
    private WSAServProfile wsmanager;

    private String myId;
    private String serverId;
    private int localId = 0;

    /**
     * Some HTTP response status codes
     */
    public static final String
	HTTP_OK = "200 OK",
	HTTP_REDIRECT = "301 Moved Permanently",
	HTTP_FORBIDDEN = "403 Forbidden",
	HTTP_NOTFOUND = "404 Not Found",
	HTTP_BADREQUEST = "400 Bad Request",
	HTTP_INTERNALERROR = "500 Internal Server Error",
	HTTP_NOTIMPLEMENTED = "501 Not Implemented";

    /**
     * Common mime types for dynamic content
     */
    public static final String
	MIME_PLAINTEXT = "text/plain",
	MIME_HTML = "text/html",
	MIME_XML = "text/xml",
	MIME_JSON = "application/json",
	MIME_RDFXML = "application/rdf+xml",
	MIME_DEFAULT_BINARY = "application/octet-stream";

    public static final int MAX_FILE_SIZE = 10000;

    public static final String HEADER = "<style type=\"text/css\">body { font-family: sans-serif } button {background-color: #DDEEFF; margin-left: 1%; border: #CCC 1px solid;}</style>";
    // ==================================================
    // Socket & server code
    // ==================================================

    /**
     * Starts a HTTP server to given port.<p>
     * Throws an IOException if the socket is already in use
     */
    public void init( Properties params, AServProtocolManager manager ) throws AServException {
	this.manager = manager;
	tcpPort = Integer.parseInt( params.getProperty( "http" ) );
	tcpHost = params.getProperty( "host" ) ;

	/*
	try {
	    final ServerSocket ss = new ServerSocket( tcpPort );
	    Thread t = new Thread( new Runnable() {
		    public void run() {
			try { while( true ) new HTTPSession( ss.accept());
			} catch ( IOException ioe ) { logger.debug( "IGNORED Exception", ioe ); }
		    }
		});
	    t.setDaemon( true );
	    t.start();
	} catch (Exception e) {
	    throw new AServException ( "Cannot launch HTTP Server" , e );
	}
	*/

	// ********************************************************************
	// JE: Jetty implementation
	server = new Server(tcpPort);

	// The handler deals with the request
	// most of its work is to deal with large content sent in specific ways 
	Handler handler = new RequestHandler();
	server.setHandler(handler);

	// Common part
	try { server.start(); }
	catch (Exception e) {
	    throw new AServException( "Cannot launch HTTP Server" , e );
	}
	//server.join();

	// ********************************************************************
	if ( params.getProperty( "wsdl" ) != null ){
	    wsmanager = new WSAServProfile();
	    if ( wsmanager != null ) wsmanager.init( params, manager );
	}
	myId = "LocalHTMLInterface";
	serverId = manager.serverURL();
	logger.info( "Launched on {}/html/", serverId );
	localId = 0;
    }
	
	class RequestHandler extends AbstractHandler{
    	public RequestHandler(){};

		public void handle(  target, HttpServletRequest request, HttpServletResponse response, int dispatch ) throws IOException, ServletException {
		    String method = request.getMethod();
		    //uri = URLDecoder.decode( request.getURI(), "iso-8859-1" );
		    // Should be decoded?
		    String uri = request.getPathInfo();
		    Properties params = new Properties();
		    try { decodeParams( request.getQueryString(), params ); }
		    catch ( Exception e) {};
		    // I do not decode them here because it is useless
		    // See below how it is done.
		    Properties header = new Properties();
		    Enumeration headerNames = request.getHeaderNames();
		    while( headerNames.hasMoreElements() ) {
				String headerName = (String)headerNames.nextElement();
				header.setProperty( headerName, request.getHeader(headerName) );
		    }

			// Get the content if any
		    // This is supposed to be only an uploaded file
		    // We use jetty MultiPartFilter to decode this file.
		    // Note that this could be made more uniform 
		    // with the text/xml part stored in a file as well.
		    String mimetype = request.getContentType();
			
			if(mimetype != null){
				handleMimetype(mimetype, request, response, params);
			}

		    // Get the answer (HTTP)
		    Response r = serve( uri, method, header, params );

		    // Return it
		    response.setContentType( r.getContentType() );
		    response.setStatus( HttpServletResponse.SC_OK );
		    response.getWriter().println( r.getData() );
		    ((Request)request).setHandled( true );
		}
		
		private void handleMimetype(String mimetype, HttpServletRequest request, HttpServletResponse response, Properties params ){
				// Multi part: the content provided by an upload HTML form
				if ( mimetype.startsWith("multipart/form-data") ) {
					handleMimetypeFormData(request, response, params);
				} else if ( mimetype.startsWith("text/xml") ) {
					handleMimetypeXml(request, params);
				// File attached to SOAP messages
				} else if ( mimetype.startsWith("application/octet-stream") ) {
					handleMimetypeOctetStream(request);
				}
		}
		
		private void handleMimetypeFormData(HttpServletRequest request, HttpServletResponse response, Properties params ){
					MultiPartFilter filter = new MultiPartFilter();
					// This is in fact useless
					ParameterServletResponseWrapper dummyResponse =
						new ParameterServletResponseWrapper( response );
					// In theory, the filter must be inited with a FilterConfig
					// filter.init( new FilterConfig);
					// This filter config must have a javax.servlet.context.tempdir attribute
					// and a ServletConxtext with parameter "deleteFiles"
					// Apparently the Jetty implementation uses System defaults
					// if no FilterConfig
					// e.g., it uses /tmp and keeps the files
					filter.doFilter( request, dummyResponse, new Chain() );
					// Apparently a bug from Jetty prevents from retrieving this
					if ( request.getParameter("pretty") != null )
						params.setProperty( "pretty", request.getParameter("pretty").toString() );
					if ( request.getAttribute("content") != null )
						params.setProperty( "filename", request.getAttribute("content").toString() );
					filter.destroy();
		}

		private void handleMimetypeXml(HttpServletRequest request, Properties params ){
					// Most likely Web service request (REST through POST)
					int length = request.getContentLength();
					if ( length > 0 ) {
						char [] mess = new char[length+1];
						try {
						new BufferedReader(new InputStreamReader(request.getInputStream())).read( mess, 0, length);
						} catch ( Exception e ) {
						logger.debug( "IGNORED Exception", e );
						}
						params.setProperty( "content", new String( mess ) );
					}
		}
		
		private void handleMimetypeOctetStream(HttpServletRequest request){
					File alignFile = new File(File.separator + "tmp" + File.separator + newId() +"XXX.rdf");
					// check if file already exists - and overwrite if necessary.
					if (alignFile.exists()) alignFile.delete();
						FileOutputStream fos = null;
						InputStream is = request.getInputStream();
			
					try {
                        fos = new FileOutputStream(alignFile);
						byte[] buffer = new byte[4096];
						int bytes=0; 
						while (true) {
							bytes = is.read(buffer);
							if (bytes < 0) break;
							fos.write(buffer, 0, bytes);
						}
					} catch (Exception e) {
					} finally {
						if (fos != null) {
							try {
								fos.flush();
								fos.close();
							} catch (java.io.IOException e3) {
								System.out.println("I/O Exception");
							}
						}
						if (is != null) {

							try {
								fos.flush();
								fos.close();
								is.close();
							} catch (java.io.IOException e3) {
								System.out.println("I/O Exception");
							}
						}
					}
					params.setProperty( "content", "" );
					params.setProperty( "filename" ,  alignFile.getAbsolutePath()  );
		}
		
	}
	


    public void close(){
	if ( wsmanager != null ) wsmanager.close();
	if ( server != null ) {
	    try { server.stop(); }
            catch (Exception e) { logger.debug( "IGNORED Exception on close", e ); }
	}
    }
    
    // ==================================================
    // API parts
    // ==================================================

    /**
     * Override this to customize the server.<p>
     *
     * (By default, this delegates to serveFile() and allows directory listing.)
     *
     * @param uri	Percent-decoded URI without parameters, for example "/index.cgi"
     * @param method	"GET", "POST" etc.
     * @param parms	Parsed, percent decoded parameters from URI and, in case of POST, data.
     * @param header	Header entries, percent decoded
     * @return HTTP response, see class Response for details
     */
    public Response serve( String uri, String method, Properties header, Properties parms ) {
		logger.debug( "{} '{}'", method, uri );

		// Convert parms to parameters
		Properties params = convertParmsToParameters(parms);
	
		int start = 0;
		while ( start < uri.length() && uri.charAt(start) == '/' ) start++;
		int end = uri.indexOf( '/', start+1 );
		String oper = "";
		if ( end != -1 ) {
			oper = uri.substring( start, end );
			start = end+1;
		} else {
			oper = uri.substring( start );
			start = uri.length();
		}

		return handleOper(oper, uri, header, start, params);
    }
	
	private Properties convertParmsToParameters(Properties parms){
		Properties params = new Properties();
		for ( String key : parms.stringPropertyNames() ) {
			//logger.trace( "  PRM: '{}' = '{}'", key, parms.getProperty( key ) );
			if ( key.startsWith( "paramn" ) ){
			params.setProperty( parms.getProperty( key ),
						parms.getProperty( "paramv"+key.substring( 6 ) ) );
			} else if ( !key.startsWith( "paramv" ) ) {
			params.setProperty( key, parms.getProperty( key ) );
			}
		}
		return params;
	}
	
	private Response handleOper(String oper, String uri, Properties header, int start, Properties params){
		Response response = null;		
		if ( oper.equals( "aserv" ) ){ // Classical web service SOAP/HTTP
			response = handleOperAserv(oper, uri, header, start, params);
		} else if ( oper.equals( "admin" ) ){ // HTML/HTTP administration
			response = adminAnswer( uri, uri.substring(start), header, params );
		} else if ( oper.equals( "alid" ) ){ // Asks for an alignment by URI
			response = handleOperAlid(oper, uri, header, start, params);
		} else if ( oper.equals( "html" ) ){ // HTML/HTTP interface
			response = htmlAnswer( uri, uri.substring(start), header, params );
		} else if ( oper.equals( "rest" ) ){ // REST/HTTP
			response = handleOperRest(oper, uri, header, start, params);
		} else if ( oper.equals( "wsdl" ) ){
			response = wsdlAnswer(uri, uri.substring(start), header, params);
		} else {
			//return serveFile( uri, header, new File("."), true );
			response = new Response( HTTP_OK, MIME_HTML, "<html><head>"+HEADER+"</head><body>"+about()+"</body></html>" );
		}
		return response;
	}
	
	private Response handleOperAserv(String oper, String uri, Properties header, int start, Properties params){
			if ( wsmanager != null ) {
			return new Response( HTTP_OK, MIME_HTML, wsmanager.protocolAnswer( uri, uri.substring(start), header, params ) );
			} else {
			// This is not correct: I shoud return an error
			// Especially in WSDL, but we are not supposed to be a Web service server at that point
			return new Response( HTTP_OK, MIME_HTML, "<html><head>"+HEADER+"</head><body>"+about()+"</body></html>" );
			}
	}
	
	private Response handleOperAlid(String oper, String uri, Properties header, int start, Properties params){
			// depending on the header
			String accept = header.getProperty( "Accept" );
			if ( accept == null ) accept = header.getProperty( "accept" );
			//logger.trace( "Accept header: {}", accept );
			if ( accept != null && !accept.contains("html") ) { // Should I check for the exact MIME_TYPE?
			if ( accept.contains( "rdf+xml" ) || accept.contains( "xml" ) ) {
				return returnAlignment( uri, MIME_RDFXML, "fr.inrialpes.exmo.align.impl.renderer.RDFRendererVisitor" );
			} else if ( accept.contains( "json" ) ) {
				return returnAlignment( uri, MIME_JSON, "fr.inrialpes.exmo.align.impl.renderer.JSONRendererVisitor" );
			}
			}
			return returnAlignment( uri, MIME_HTML, "fr.inrialpes.exmo.align.impl.renderer.HTMLRendererVisitor" );
	}
	
	private Response handleOperRest(String oper, String uri, Properties header, int start, Properties params){
			params.setProperty( "restful", "true" );
			//The return format is XML by default 
			if ( params.getProperty("return") == null || (params.getProperty("return")).equals("XML") ) 
				params.setProperty( "renderer", "XML" );
			else 
				params.setProperty( "renderer", "HTML" );

			if ( wsmanager != null ) {
			if( (params.getProperty("renderer")).equals("HTML") )
				return htmlAnswer( uri, uri.substring(start), header, params );
			else {
				return new Response( HTTP_OK, MIME_XML, wsmanager.protocolAnswer( uri, uri.substring(start), header, params ) );
			}
			} else {
			//Message err = new ErrorMsg(int surr, Message rep, String from, String to, String cont, params );
			if( (params.getProperty("renderer")).equals("HTML") ) {
				return new Response( HTTP_OK, MIME_HTML, "<html><head>"+HEADER+"</head><body>"+"<ErrMsg>No service launched</ErrMsg>"+"<hr /><center><small><a href=\".\">Alignment server</a></small></center></body></html>" );
			} else {
				return new Response( HTTP_OK, MIME_XML, "<SystemErrorMsg>No service launched</SystemErrorMsg>" );
			}
			}
	}

    protected String about() {
	return "<h1>Alignment server</h1><center>"+AlignmentService.class.getPackage().getImplementationTitle()+" "+AlignmentService.class.getPackage().getImplementationVersion()+"<br />"
	    + "<center><a href=\"html/\">Access</a></center>"
	    + "(C) INRIA, 2006-2013<br />"
	    + "<a href=\"http://alignapi.gforge.inria.fr\">http://alignapi.gforge.inria.fr</a><br />"
	    + "</center>";
    }

    /**
     * HTTP administration interface
     * Allows some limited administration of the server through HTTP
     */
    public Response adminAnswer( String uri, String perf, Properties header, Properties params ) {
		//logger.trace( "ADMIN[{}]", perf);
		String msg = "Cannot understand: "+perf;

		if ( perf.equals("") ) {
			msg = "<h1>Alignment server administration</h1>";
			msg += "<form action=\"listmethods\"><button title=\"List embedded plug-ins\" type=\"submit\">Embedded classes</button></form>";
			if ( wsmanager != null )
			msg += "<form action=\"/wsdl\"><button title=\"WSDL Description\" type=\"submit\">WSDL Description</button></form>";
			msg += "<form action=\"prmsqlquery\"><button title=\"Query the SQL database (unavailable)\" type=\"submit\">SQL Query</button></form>";
			msg += "<form action=\"prmflush\"><button title=\"Free memory by unloading correspondences\" type=\"submit\">Flush caches</button></form>";
			msg += "<form action=\"prmreset\"><button title=\"Restore launching state (reload from database)\" type=\"submit\">Reset server</button></form>";
			//	    msg += "<form action=\"shutdown\"><button title=\"Shutdown server\" type=\"submit\">Shutdown</button></form>";
			msg += "<form action=\"..\"><button title=\"About...\" type=\"submit\">About</button></form>";
			msg += "<form action=\"../html/\"><button style=\"background-color: lightpink;\" title=\"Back to user menu\" type=\"submit\">User interface</button></form>";
		} else {
			msg = handlePerf(perf, params);
		}
		return new Response( HTTP_OK, MIME_HTML, "<html><head>"+HEADER+"</head><body>"+msg+"<hr /><center><small><a href=\".\">Alignment server administration</a></small></center></body></html>" );
    }
	
	private String handlePerf(String perf, Properties params){
		HashMap<String,String> msgMap = new HashMap<String,String>();
		msgMap.put("listmethods", handlePerfListmethods());
		msgMap.put("wsdl", handlePerfWsdl());
		msgMap.put("argline", "<h1>Command line arguments</h1>\n<pre>\n"+manager.argline()+"\n<pre>\n");
		msgMap.put("prmsqlquery", "<h1>SQL query</h1><form action=\"sqlquery\">Query:<br /><textarea name=\"query\" rows=\"20\" cols=\"80\">SELECT \nFROM \nWHERE </textarea> (sql)<br /><small>An SQL SELECT query</small><br /><input type=\"submit\" value=\"Query\"/></form>");
		String answer = manager.query( params.getProperty("query") );
		msgMap.put("sqlquery", "<pre>"+answer+"</pre>");
		msgMap.put("about", about());
		msgMap.put("shutdown", "<h1>Server shut down</h1>");
		msgMap.put("prmreset", "<h1>Alignment server reset from database</h1>");
		msgMap.put("prmflush", "<h1>Cache has been flushed</h1>");
		msgMap.put("addservice", perf);
		msgMap.put("addmethod", perf);
		msgMap.put("addrenderer", perf);
		
		String msg = msgMap.get(perf);

		if ( perf.equals("shutdown") ){
			manager.shutdown();
		} else if ( perf.equals("prmreset") ){
			manager.reset();
		} else if ( perf.equals("prmflush") ){
			manager.flush();
		}
		return msg;
	}
	
	private String handlePerfListmethods(){
			String msg = "<h1>Embedded classes</h1>\n<h2>Methods</h2><ul compact=\"1\">";
			for( String m : manager.listmethods() ) {
				msg += "<li>"+m+"</li>";
			}
			msg += "</ul>";
			msg += "<h2>Renderers</h2><ul compact=\"1\">";
			for( String m : manager.listrenderers() ) {
				msg += "<li>"+m+"</li>";
			}
			msg += "</ul>";
			msg += "<h2>Services</h2><ul compact=\"1\">";
			for( String m : manager.listservices() ) {
				msg += "<li>"+m+"</li>";
			}
			msg += "</ul>";
			msg += "<h2>Evaluators</h2><ul compact=\"1\">";
			for( String m : manager.listevaluators() ) {
				msg += "<li>"+m+"</li>";
			}
			msg += "</ul>";
			return msg;
	}
	
	private String handlePerfWsdl(){
			String msg;
			if ( wsmanager != null ){
				msg = "<pre>"+WSAServProfile.wsdlAnswer( false )+"</pre>";
			} else {
				msg = "Error: the server does not have Web service capabilities (use -W switch)";
			}
			return msg;
	}
	

    /**
     * Returns the alignment in negociated format
     */
    public Response returnAlignment( String uri, String mime, String method ) {
	Properties params = new Properties();
	params.setProperty( "id", manager.serverURL()+uri );
	params.setProperty( "method", method );
	logger.trace( "Bloody URI : {}", manager.serverURL()+uri);
	Message answer = manager.render( new Message(newId(),(Message)null,myId,serverId,"", params) );
	if ( answer instanceof ErrorMsg ) {
	    return new Response( HTTP_NOTFOUND, MIME_PLAINTEXT, "Alignment server: unknown alignment : "+answer.getContent() );
	} else {
	    return new Response( HTTP_OK, mime, answer.getContent() );
	}
    }

    /**
     * User friendly HTTP interface
     * uses the protocol but offers user-targeted interaction
     */
    public Response htmlAnswer( String uri, String perf, Properties header, Properties params ) {
	//logger.trace("HTML[{}]", perf );
	// REST get
	String msg = "Cannot understand command "+perf;
	
	if ( perf.equals("") ) {
	    msg = "<h1>Alignment server commands</h1>";
	    msg += "<form action=\"../html/listalignments\"><button title=\"List of all the alignments stored in the server\" type=\"submit\">Available alignments</button></form>";
	    msg += "<form action=\"prmload\"><button title=\"Upload an existing alignment in this server\" type=\"submit\">Load alignments</button></form>";
	    msg += "<form action=\"prmfind\"><button title=\"Find existing alignements between two ontologies\" type=\"submit\">Find alignment</button></form>";
	    msg += "<form action=\"prmmatch\"><button title=\"Apply matchers to ontologies for obtaining an alignment\" type=\"submit\">Match ontologies</button></form>";
	    msg += "<form action=\"prmtrim\"><button title=\"Trim an alignment above some threshold\" type=\"submit\">Trim alignment</button></form>";
	    msg += "<form action=\"prminv\"><button title=\"Swap the two ontologies of an alignment\" type=\"submit\">Invert alignment</button></form>";
	    msg += "<form action=\"prmstore\"><button title=\"Persistently store an alignent in this server\" type=\"submit\" >Store alignment</button></form>";
	    msg += "<form action=\"prmretrieve\"><button title=\"Render an alignment in a particular format\" type=\"submit\">Render alignment</button></form>";
	    msg += "<form action=\"prmeval\"><button title=\"Evaluation of an alignment\" type=\"submit\">Evaluate alignment</button></form>";
	    msg += "<form action=\"prmdiff\"><button title=\"Compare two alignments\" type=\"submit\">Compare alignment</button></form>";
	    msg += "<form action=\"../admin/\"><button style=\"background-color: lightpink;\" title=\"Server management functions\" type=\"submit\">Server management</button></form>";
	} else {
		HashMap<String,HtmlAnswerHandler> map = new HashMap<>();
		map.put("listalignments", new HtmlAnswerListAlignments());
		map.put("manalignments", new HtmlAnswerManalignments());
		map.put("errrazze", new HtmlAnswerErrrazze());
		map.put("prmstore", new HtmlAnswerPrmstore());
		map.put("store", new HtmlAnswerStore());
		map.put("prmtrim", new HtmlAnswerPrmtrim());
		map.put("trim", new HtmlAnswerTrim());
		map.put("prminv", new HtmlAnswerPrminv());
		map.put("inv", new HtmlAnswerInv());
		map.put("prmmatch", new HtmlAnswerPrmmatch());
		map.put("match", new HtmlAnswerMatch());
		map.put("find", new HtmlAnswerFind());
		map.put("corresp", new HtmlAnswerCorresp());
		map.put("prmretrieve", new HtmlAnswerPrmretrieve());
		map.put("retrieve", new HtmlAnswerRetrieve());
		map.put("prmmetadata", new HtmlAnswerPrmmetadata());
		map.put("metadata", new HtmlAnswerMetadata());
		map.put("load", new HtmlAnswerLoad());
		map.put("prmtranslate", new HtmlAnswerPrmtranslate());
		map.put("translate", new HtmlAnswerTranslate());
		map.put("prmeval", new HtmlAnswerPrmeval());
		map.put("eval", new HtmlAnswerEval());
		map.put("prmdiff", new HtmlAnswerPrmdiff());
		map.put("diff", new HtmlAnswerDiff());
		map.put("prmdiff", new HtmlAnswerPrmdiff());
		msg = map.get(perf).handle(uri, params);
	}
	
	
	return new Response( HTTP_OK, MIME_HTML, "<html><head>"+HEADER+"</head><body>"+msg+"<hr /><center><small><a href=\".\">Alignment server</a></small></center></body></html>" );
    }
	
	interface HtmlAnswerHandler{
		public String handle(String uri, Properties params);
	}
	
	class HtmlAnswerListAlignments implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		URI uri1 = null;	
	    String u1 = params.getProperty("uri1");	    
		getUriFromString(u1, uri1);
		
	    URI uri2 = null;
	    String u2 = params.getProperty("uri2");
		getUriFromString(u2, uri2);
		
	    // Add two onto checklist
	    Collection<URI> ontologies = manager.ontologies();
	    msg = "<h1>Available alignments</h1><form action=\"listalignments\">";
	    msg += "Onto1:  <select name=\"uri1\"><option value=\"all\"";
	    if ( uri1 == null ) msg += " selected=\"1\"";
	    msg += ">all</option>";
		msg = getMessageFromOtologies(msg, ontologies, uri1);
		
	    msg += "</select>";
	    msg += "Onto2:  <select name=\"uri2\"><option value=\"all\"";
	    if ( uri2 == null ) msg += " selected=\"1\"";
	    msg += ">all</option>";
		msg = getMessageFromOtologies(msg, ontologies, uri2);
		
	    msg += "</select>";
	    msg += "&nbsp;<input type=\"submit\" value=\"Restrict\"/></form><ul compact=\"1\">";
	    // would be better as a JavaScript which updates
	    Collection<Alignment> alignments = null;
	    if ( uri1 == null && uri2 == null ) {
			alignments = manager.alignments();
	    } else {
			alignments = manager.alignments( uri1, uri2 );
	    }

	    for ( Alignment al : alignments ) {
			String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID );
			String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
			if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
			//msg += "<li><a href=\"../html/retrieve?method=fr.inrialpes.exmo.align.impl.renderer.HTMLRendererVisitor&id="+id+"\">"+pid+"</a></li>";
			msg += "<li><a href=\""+id+"\">"+pid+"</a></li>";
	    }
	    msg += "</ul>";
		return msg;
		}
		
		private void getUriFromString(String u, URI uri){
		try {
			if ( u != null && !u.equals("all") ) uri = new URI( u );
	    } catch ( URISyntaxException usex ) {
			logger.debug( "IGNORED Invalid URI parameter", usex );
	    };
		}
	
		private String getMessageFromOtologies(String msg, Collection<URI> ontologies, URI uri){
		for ( URI ont : ontologies ) {
			msg += "<option";
			if ( ont.equals( uri ) ) msg += " selected =\"1\"";
			msg += " value=\""+ont+"\">"+ont+"</option>"; //simplify
	    }
		return msg;
		}
	}	
	
	class HtmlAnswerManalignments implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
			String msg = "";
			msg = "<h1>Available alignments</h1><ul compact=\"1\">";
			for ( Alignment al : manager.alignments() ) {
			String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID );
			String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
			if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
			//msg += "<li><a href=\"../html/retrieve?method=fr.inrialpes.exmo.align.impl.renderer.HTMLRendererVisitor&id="+id+"\">"+pid+"</a> "+al.nbCells()+" <a href=\"../html/errrazze?id="+id+"\">DEL</a></li>";
			msg += "<li><a href=\""+id+"\">"+pid+"</a> "+al.nbCells()+" <a href=\"../html/errrazze?id="+id+"\">DEL</a></li>";
			}
			msg += "</ul>";
			return msg;
		}
	}	
	
	class HtmlAnswerErrrazze implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		String id = params.getProperty("id");
	    if ( id != null && !id.equals("") ) { // Erase it
		Message answer = manager.erase( new Message(newId(),(Message)null,myId,serverId,id, params) );
		if ( answer instanceof ErrorMsg ) {
		    msg = testErrorMessages( answer, params );
		} else {
		    msg = "<h1>Alignment deleted</h1>";
		    msg += displayAnswer( answer, params );
		}
	    }
		return msg;
		}
	}	
	
	class HtmlAnswerPrmstore implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		msg = "<h1>Store an alignment</h1><form action=\"store\">";
	    msg += "Alignment id:  <select name=\"id\">";
	    // JE: only those non stored please (retrieve metadata + stored)
	    for ( Alignment al : manager.alignments() ) {
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		params.setProperty("id", id);
		if ( !manager.storedAlignment( new Message(newId(),(Message)null,myId,serverId,"", params ) ) ){
		    String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		    if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		    msg += "<option value=\""+id+"\">"+pid+"</option>";
		}
	    }
	    msg += "</select><br />";
	    msg += "<input type=\"submit\" value=\"Store\"/></form>";
		return msg;
		}
	}	

	class HtmlAnswerStore implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		// here should be done the switch between store and load/store
	    String id = params.getProperty("id");
	    String url = params.getProperty("url");
	    if ( url != null && !url.equals("") ) { // Load the URL
		Message answer = manager.load( new Message(newId(),(Message)null,myId,serverId,"", params) );
		if ( answer instanceof ErrorMsg ) {
		    msg = testErrorMessages( answer, params );
		} else {
		    id = answer.getContent();
		}
	    }
	    if ( id != null ){ // Store it
		Message answer = manager.store( new Message(newId(),(Message)null,myId,serverId,id, params) );
		if ( answer instanceof ErrorMsg ) {
		    msg = testErrorMessages( answer, params );
		} else {
		    msg = "<h1>Alignment stored</h1>";
		    msg += displayAnswer( answer, params );
		}
	    }
		return msg;
		}
	}

	class HtmlAnswerPrmtrim implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		String sel = params.getProperty("id");
	    msg ="<h1>Trim alignments</h1><form action=\"trim\">";
	    msg += "Alignment id:  <select name=\"id\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		if ( sel != null && sel.equals( id ) ){
		    msg += "<option selected=\"1\" value=\""+id+"\">"+pid+"</option>";
		} else {
		    msg += "<option value=\""+id+"\">"+pid+"</option>";
		}
	    }
	    msg += "</select><br />";
	    msg += "Type: <select name=\"type\"><option value=\"hard\">hard</option><option value=\"perc\">perc</option><option value=\"best\">best</option><option value=\"span\">span</option><option value=\"prop\">prop</option></select><br />Threshold: <input type=\"text\" name=\"threshold\" size=\"4\"/> <small>A value between 0. and 1. with 2 digits</small><br /><input type=\"submit\" name=\"action\" value=\"Trim\"/><br /></form>";

		return msg;
		}
	}	

	class HtmlAnswerTrim implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		String id = params.getProperty("id");
	    String threshold = params.getProperty("threshold");
	    if ( id != null && !id.equals("") && threshold != null && !threshold.equals("") ){ // Trim it
		Message answer = manager.trim( new Message(newId(),(Message)null,myId,serverId,id, params) );
		if ( answer instanceof ErrorMsg ) {
		    msg = testErrorMessages( answer, params );
		} else {
		    msg = "<h1>Alignment trimed</h1>";
		    msg += displayAnswer( answer, params );
		}
	    }
		return msg;
		}
	}	

	
	class HtmlAnswerPrminv implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		msg ="<h1>Invert alignment</h1><form action=\"inv\">";
	    msg += "Alignment id:  <select name=\"id\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg += "<input type=\"submit\" name=\"action\" value=\"Invert\"/><br /></form>";
		return msg;
		}
	}	

	class HtmlAnswerInv implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		String id = params.getProperty("id");
	    if ( id != null && !id.equals("") ){ // Invert it
		Message answer = manager.inverse( new Message(newId(),(Message)null,myId,serverId,id, params) );
		if ( answer instanceof ErrorMsg ) {
		    msg = testErrorMessages( answer, params );
		} else {
		    msg = "<h1>Alignment inverted</h1>";
		    msg += displayAnswer( answer, params );
		}
	    }
		return msg;
		}
	}	
	
	class HtmlAnswerPrmmatch implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		String RESTOnto1 = "";
	    String RESTOnto2 = "";
	    String readonlyOnto = "";
	    //Ontologies from Cupboard may be already provided here.
	    if ( params.getProperty("restful") != null && 
		 (params.getProperty("renderer")).equals("HTML") ) {
		RESTOnto1 = params.getProperty("onto1");
		RESTOnto2 = params.getProperty("onto2");
		//if(RESTOnto1 != null && !RESTOnto1.equals("") && RESTOnto2 != null && !RESTOnto2.equals("")) 
		readonlyOnto = "readonly=\"readonly\"";
	    }
	    msg ="<h1>Match ontologies</h1><form action=\"match\">Ontology 1: <input type=\"text\" name=\"onto1\" size=\"80\" value="+RESTOnto1+" " +readonlyOnto+"> (uri)<br />Ontology 2: <input type=\"text\" name=\"onto2\" size=\"80\" value="+RESTOnto2+" "+readonlyOnto+ "> (uri)<br /><small>These are the URL of places where to find these ontologies. They must be reachable by the server (i.e., file:// URI are acceptable if they are on the server)</small><br /><!--input type=\"submit\" name=\"action\" value=\"Find\"/><br /-->Methods: <select name=\"method\">";
	    for( String id : manager.listmethods() ) {
		msg += "<option value=\""+id+"\">"+id+"</option>";
	    }
	    msg += "</select><br />Initial alignment id:  <select name=\"id\"><option value=\"\" selected=\"1\"></option>";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg += "Pretty name: <input type=\"text\" name=\"pretty\" size=\"80\"/><br />";
	    msg += "<input type=\"submit\" name=\"action\" value=\"Match\"/>";
	    msg += "  <input type=\"checkbox\" name=\"force\" /> Force <input type=\"checkbox\" name=\"async\" /> Asynchronous<br />";
	    msg += "Additional parameters:<br /><input type=\"text\" name=\"paramn1\" size=\"15\"/> = <input type=\"text\" name=\"paramv1\" size=\"65\"/><br /><input type=\"text\" name=\"paramn2\" size=\"15\"/> = <input type=\"text\" name=\"paramv2\" size=\"65\"/><br /><input type=\"text\" name=\"paramn3\" size=\"15\"/> = <input type=\"text\" name=\"paramv3\" size=\"65\"/><br /><input type=\"text\" name=\"paramn4\" size=\"15\"/> = <input type=\"text\" name=\"paramv4\" size=\"65\"/></form>";

		return msg;
		}
	}	
	
	class HtmlAnswerMatch implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    Message answer = manager.align( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		msg = "<h1>Alignment results</h1>";
		msg += displayAnswer( answer, params );
	    }
		return msg;
		}
	}	
	
	class HtmlAnswerFind implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    Message answer = manager.existingAlignments( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		msg = "<h1>Found alignments</h1>";
		msg += displayAnswer( answer, params );
	    }
		return msg;
		}
	}	

	
	class HtmlAnswerCorresp implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    Message answer = manager.findCorrespondences( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		msg = "<h1>Found correspondences</h1>";
		msg += displayAnswer( answer, params );
	    }
		return msg;
		}
	}	
	
	class HtmlAnswerPrmretrieve implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    String sel = params.getProperty("id");
	    msg = "<h1>Retrieve alignment</h1><form action=\"retrieve\">";
	    msg += "Alignment id:  <select name=\"id\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		if ( sel != null && sel.equals( id ) ){
		    msg += "<option selected=\"1\" value=\""+id+"\">"+pid+"</option>";
		} else {
		    msg += "<option value=\""+id+"\">"+pid+"</option>";
		}
	    }
	    msg += "</select><br />";
	    msg += "Rendering: <select name=\"method\">";
	    for( String id : manager.listrenderers() ) {
		msg += "<option value=\""+id+"\">"+id+"</option>";
	    }
	    msg += "</select><br /><input type=\"submit\" value=\"Retrieve\"/></form>";
		return msg;
		}
	}	
	
	class HtmlAnswerRetrieve implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    Message answer = manager.render( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		// Depending on the type we should change the MIME type
		// This should be returned in answer.getParameters()
		return new Response( HTTP_OK, MIME_HTML, answer.getContent() );
	    }
		return msg;
		}
	}	

	
	class HtmlAnswerPrmmetadata implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    msg = "<h1>Retrieve alignment metadata</h1><form action=\"metadata\">";
	    msg += "Alignment id:  <select name=\"id\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br /><input type=\"submit\" value=\"Get metadata\"/></form>";
		return msg;
		}
	}	
	
	class HtmlAnswerMetadata implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    if( params.getProperty("renderer") == null || (params.getProperty("renderer")).equals("HTML") )
	    	params.setProperty("method", "fr.inrialpes.exmo.align.impl.renderer.HTMLMetadataRendererVisitor");
	    else
		params.setProperty("method", "fr.inrialpes.exmo.align.impl.renderer.XMLMetadataRendererVisitor");
	    Message answer = manager.render( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    //logger.trace( "Content: {}", answer.getContent() );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		// Depending on the type we should change the MIME type
		return new Response( HTTP_OK, MIME_HTML, answer.getContent() );
	    }
	    // render
	    // Alignment in HTML can be rendre or metadata+tuples
		return msg;
		}
	}	
	
	class HtmlAnswerLoad implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    Message answer = manager.load( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
			msg = testErrorMessages( answer, params );
	    } else {
			msg = "<h1>Alignment loaded</h1>";
			msg += displayAnswer( answer, params );
	    }
		return msg;
		}
	}	

	
	class HtmlAnswerPrmtranslate implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    msg = "<h1>Translate query</h1><form action=\"translate\">";
	    msg += "Alignment id:  <select name=\"id\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg += "SPARQL query:<br /> <textarea name=\"query\" rows=\"20\" cols=\"80\">PREFIX foaf: <http://xmlns.com/foaf/0.1/>\nSELECT *\nFROM <>\nWHERE {\n\n}</textarea> (SPARQL)<br /><small>A SPARQL query (PREFIX prefix: &lt;uri&gt; SELECT variables FROM &lt;url&gt; WHERE { triples })</small><br /><input type=\"submit\" value=\"Translate\"/></form>";

		return msg;
		}
	}	

	
	class HtmlAnswerTranslate implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
		Message answer = manager.translate( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		msg = "<h1>Message translation</h1>";
		msg += "<h2>Initial message</h2><pre>"+(params.getProperty("query")).replaceAll("&", "&amp;").replaceAll("<", "&lt;")+"</pre>";
		msg += "<h2>Translated message</h2><pre>";
		msg += answer.HTMLString().replaceAll("&", "&amp;").replaceAll("<", "&lt;");
		msg += "</pre>";
	    }
		return msg;
		}
	}	
	
	class HtmlAnswerPrmeval implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
	    String msg = "";
		msg ="<h1>Evaluate alignment</h1><form action=\"eval\">";
	    msg += "Alignment to evaluate: ";
	    msg += "<select name=\"id\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg +="Reference alignment: ";
	    msg += "<select name=\"ref\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg += "Evaluator: ";
	    msg += "<select name=\"method\">";
	    for( String id : manager.listevaluators() ) {
		msg += "<option value=\""+id+"\">"+id+"</option>";
	    }
	    msg += "</select><br /><input type=\"submit\" name=\"action\" value=\"Evaluate\"/>\n";
	    msg += "</form>\n";
		return msg;
		}
	}	

	class HtmlAnswerEval implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";
	    Message answer = manager.eval( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		msg = "<h1>Evaluation results</h1>";
		msg += displayAnswer( answer, params );
	    }
		return msg;
		}
	}	

	
	class HtmlAnswerPrmdiff implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";

		msg ="<h1>Compare alignments</h1><form action=\"diff\">";
	    msg += "First alignment: ";
	    msg += "<select name=\"id1\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg +="Second alignment: ";
	    msg += "<select name=\"id2\">";
	    for( Alignment al: manager.alignments() ){
		String id = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.ID);
		String pid = al.getExtension( Namespace.ALIGNMENT.uri, Annotations.PRETTY );
		if ( pid == null ) pid = id; else pid = id+" ("+pid+")";
		msg += "<option value=\""+id+"\">"+pid+"</option>";
	    }
	    msg += "</select><br />";
	    msg += "<br /><input type=\"submit\" name=\"action\" value=\"Compare\"/>\n";
	    msg += "</form>\n";
		
		return msg;
		}
	}	
	
	class HtmlAnswerDiff implements HtmlAnswerHandler{
		public String handle(String uri, Properties params){
		String msg = "";

	    Message answer = manager.diff( new Message(newId(),(Message)null,myId,serverId,"", params) );
	    if ( answer instanceof ErrorMsg ) {
		msg = testErrorMessages( answer, params );
	    } else {
		msg = "<h1>Comparison results</h1>";
		msg += displayAnswer( answer, params );
	    }
		
		return msg;
		}
	}	
		
	
	


    // ===============================================
    // Util

    public Response wsdlAnswer(String uri, String perf, Properties header, Properties params  ) {
	return new Response( HTTP_OK, MIME_XML, WSAServProfile.wsdlAnswer( false ) );
    }	 

    private String testErrorMessages( Message answer, Properties param ) {
	if ( param.getProperty("restful") != null ) {
	    return answer.RESTString();
	} else {
	    return "<h1>Alignment error</h1>"+answer.HTMLString();
	}
    }

    private String displayAnswer( Message answer, Properties param ) {
		String result = null;
		if( param.getProperty("restful") != null ) {
			result = displayAnswerRestfulNotNull(answer, param);
		} else {
			result = displayAnswerRestfulNull(answer, param);
		}
		return result;
    }
	
	private String displayAnswerRestfulNotNull(Message answer, Properties param){
			String result = null;
			if( param.getProperty("return").equals("HTML") ) {
				result = answer.HTMLRESTString();
				if ( answer instanceof AlignmentId && ( answer.getParameters() == null || answer.getParameters().getProperty("async") == null ) ){
					result += "<table><tr>";
					result += "<td><form action=\"getID\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"GetID\"  disabled=\"disabled\"/></form></td>";
					result += "<td><form action=\"metadata\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"Metadata\"/></form></td>";
					result += "</tr></table>";
				} else if( answer instanceof AlignmentIds && ( answer.getParameters() == null || answer.getParameters().getProperty("async") == null )) {
					result = answer.HTMLRESTString();
				}
			} else 
				result = answer.RESTString();
			return result;
	}
	
	private String displayAnswerRestfulNull(Message answer, Properties param){			
			String result = null;
			result = answer.HTMLString();
			// Improved return
			if ( answer instanceof AlignmentId && ( answer.getParameters() == null || answer.getParameters().getProperty("async") == null ) ){
				result += "<table><tr>";
				// STORE
				result += "<td><form action=\"store\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"Store\"/></form></td>";
				// TRIM (2)
				result += "<td><form action=\"prmtrim\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"Trim\"/></form></td>";
				// RETRIEVE (1)
				result += "<td><form action=\"prmretrieve\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"Show\"/></form></td>";
				// Note at that point it is not possible to get the methods
				// COMPARE (2)
				// INV
				result += "<td><form action=\"inv\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"Invert\"/></form></td>";
				result += "</tr></table>";
			} else if ( answer instanceof EvaluationId && ( answer.getParameters() == null || answer.getParameters().getProperty("async") == null ) ){
				result += "<table><tr>";
				// STORE (the value should be the id here, not the content)
				result += "<td><form action=\"saveeval\"><input type=\"hidden\" name=\"id\" value=\""+answer.getContent()+"\"/><input type=\"submit\" name=\"action\" value=\"Store\"/></form></td>";
				result += "</tr></table>";
			}
			return result;
	}

    private int newId() { return localId++; }

    private void decodeParams( String params, Properties p ) throws InterruptedException {
	if ( params == null ) return;
	
	for ( String next : params.split("&") ) {
	    int sep = next.indexOf( '=' );
	    if ( sep >= 0 ){
		try {
		    p.put( URLDecoder.decode( next.substring( 0, sep ), "iso-8859-1" ).trim(),
			   // JE: URLDecoder allows for : and / but not #
			   URLDecoder.decode( next.substring( sep+1 ), "iso-8859-1" ));
		} catch (Exception e) {}; //never thrown
	    }
	}
    }

    // ==================================================
    // HTTP Machinery

    /**
     * HTTP response.
     * Return one of these from serve().
     */
    public class Response {
	/**
	 * Default constructor: response = HTTP_OK, data = mime = 'null'
	 */
	public Response() {
	    this.status = HTTP_OK;
	}

	/**
	 * Basic constructor.
	 */
	public Response( String status, String mimeType, InputStream data ) {
	    this.status = status;
	    this.mimeType = mimeType;
	    this.data = data;
	}

	/**
	 * Convenience method that makes an InputStream out of
	 * given text.
	 */
	public Response( String status, String mimeType, String txt ) {
	    this.status = status;
	    this.mimeType = mimeType;
	    this.data = new ByteArrayInputStream( txt.getBytes());
	    // JE: Added
	    this.msg = txt;
	}

	/**
	 * Adds given line to the header.
	 */
	public void addHeader( String name, String value ) {
	    header.put( name, value );
	}


	/**
	 * HTTP status code after processing, e.g. "200 OK", HTTP_OK
	 */
	public String status;

	/**
	 * MIME type of content, e.g. "text/html"
	 */
	public String mimeType;

	/**
	 * Data of the response, may be null.
	 */
	public InputStream data;

	/**
	 * Headers for the HTTP response. Use addHeader()
	 * to add lines.
	 */
	public Properties header = new Properties();
	// JE: Added for testing Jetty
	public String msg;
	public String getStatus() { return status; };
	public String getContentType() { return mimeType; }
	public String getData() { return msg; }

    }

    /**
     * Two private cclasses for retrieving parameters
     */
    private class ParameterServletResponseWrapper extends ServletResponseWrapper  {
	private Map parameters;

	public ParameterServletResponseWrapper( ServletResponse r ){
	    super(r);
	};

	public Map getParameterMap(){ return parameters; }
 
	public void setParameterMap( Map m ){ parameters = m; }
 
     }

    private class Chain implements FilterChain {
 
	public void doFilter( ServletRequest request, ServletResponse response)
	    throws IOException, ServletException {
	    if ( response instanceof ParameterServletResponseWrapper &&
		 request instanceof ServletRequestWrapper ) {
		((ParameterServletResponseWrapper)response).setParameterMap( ((ServletRequestWrapper)request).getParameterMap() );
	    }
         }
 
     }
}

