package freenet.clients.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.l10n.L10n;
import freenet.node.DarknetPeerNode;
import freenet.node.DarknetPeerNodeStatus;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PeerManager;
import freenet.node.PeerNodeStatus;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.api.HTTPRequest;

public class DarknetConnectionsToadlet extends ConnectionsToadlet {
	
	DarknetConnectionsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(n, core, client);
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}

	private static String l10n(String string) {
		return L10n.getString("DarknetConnectionsToadlet."+string);
	}
	
	protected class DarknetComparator extends ComparatorByStatus {

		DarknetComparator(String sortBy, boolean reversed) {
			super(sortBy, reversed);
		}
	
		protected int customCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode, String sortBy) {
			if(sortBy.equals("name")) {
				return ((DarknetPeerNodeStatus)firstNode).getName().compareToIgnoreCase(((DarknetPeerNodeStatus)secondNode).getName());
			}else if(sortBy.equals("privnote")){
				return ((DarknetPeerNodeStatus)firstNode).getPrivateDarknetCommentNote().compareToIgnoreCase(((DarknetPeerNodeStatus)secondNode).getPrivateDarknetCommentNote());
			} else
				return super.customCompare(firstNode, secondNode, sortBy);
		}
		
		/** Default comparison, after taking into account status */
		protected int lastResortCompare(PeerNodeStatus firstNode, PeerNodeStatus secondNode) {
			return ((DarknetPeerNodeStatus)firstNode).getName().compareToIgnoreCase(((DarknetPeerNodeStatus)secondNode).getName());
		}

	}
	
	protected Comparator comparator(String sortBy, boolean reversed) {
		return new DarknetComparator(sortBy, reversed);
	}
		
	public void handlePost(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			if(logMINOR) Logger.minor(this, "No password ("+pass+" should be "+core.formPassword+ ')');
			return;
		}
		
		if (request.isPartSet("add")) {
			// add a new node
			String urltext = request.getPartAsString("url", 100);
			urltext = urltext.trim();
			String reftext = request.getPartAsString("ref", 2000);
			reftext = reftext.trim();
			if (reftext.length() < 200) {
				reftext = request.getPartAsString("reffile", 2000);
				reftext = reftext.trim();
			}
			String privateComment = request.getPartAsString("peerPrivateNote", 250).trim();
			
			StringBuffer ref = new StringBuffer(1024);
			if (urltext.length() > 0) {
				// fetch reference from a URL
				BufferedReader in = null;
				try {
					URL url = new URL(urltext);
					URLConnection uc = url.openConnection();
					// FIXME get charset encoding from uc.getContentType()
					in = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					String line;
					while ( (line = in.readLine()) != null) {
						ref.append( line ).append('\n');
					}
				} catch (IOException e) {
					this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), L10n.getString("DarknetConnectionsToadlet.cantFetchNoderefURL", new String[] { "url" }, new String[] { urltext }));
					return;
				} finally {
					if( in != null ){
						in.close();
					}
				}
			} else if (reftext.length() > 0) {
				// read from post data or file upload
				// this slightly scary looking regexp chops any extra characters off the beginning or ends of lines and removes extra line breaks
				ref = new StringBuffer(reftext.replaceAll(".*?((?:[\\w,\\.]+\\=[^\r\n]+?)|(?:End))[ \\t]*(?:\\r?\\n)+", "$1\n"));
			} else {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("noRefOrURL"));
				request.freeParts();
				return;
			}
			ref = new StringBuffer(ref.toString().trim());

			request.freeParts();
			// we have a node reference in ref
			SimpleFieldSet fs;
			
			try {
				fs = new SimpleFieldSet(ref.toString(), false, true);
				if(!fs.getEndMarker().endsWith("End")) {
					sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"),
							L10n.getString("DarknetConnectionsToadlet.cantParseWrongEnding", new String[] { "end" }, new String[] { fs.getEndMarker() }));
					return;
				}
				fs.setEndMarker("End"); // It's always End ; the regex above doesn't always grok this
			} catch (IOException e) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), 
						L10n.getString("DarknetConnectionsToadlet.cantParseTryAgain", new String[] { "error" }, new String[] { e.toString() }));
				return;
			} catch (Throwable t) {
				this.sendErrorPage(ctx, l10n("failedToAddNodeInternalErrorTitle"), l10n("failedToAddNodeInternalError"), t);
				return;
			}
			DarknetPeerNode pn;
			try {
				pn = node.createNewDarknetNode(fs);
				pn.setPrivateDarknetCommentNote(privateComment);
			} catch (FSParseException e1) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"),
						L10n.getString("DarknetConnectionsToadlet.cantParseTryAgain", new String[] { "error" }, new String[] { e1.toString() }));
				return;
			} catch (PeerParseException e1) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), 
						L10n.getString("DarknetConnectionsToadlet.cantParseTryAgain", new String[] { "error" }, new String[] { e1.toString() }));
				return;
			} catch (ReferenceSignatureVerificationException e1){
				HTMLNode node = new HTMLNode("div");
				node.addChild("#", L10n.getString("DarknetConnectionsToadlet.invalidSignature", new String[] { "error" }, new String[] { e1.toString() }));
				node.addChild("br");
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), node);
				return;
			} catch (Throwable t) {
				this.sendErrorPage(ctx, l10n("failedToAddNodeInternalErrorTitle"), l10n("failedToAddNodeInternalError"), t);
				return;
			}
			if(Arrays.equals(pn.getIdentity(), node.getDarknetIdentity())) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("triedToAddSelf"));
				return;
			}
			if(!this.node.addDarknetConnection(pn)) {
				this.sendErrorPage(ctx, 200, l10n("failedToAddNodeTitle"), l10n("alreadyInReferences"));
				return;
			}
			
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("send_n2ntm")) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("sendMessageTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			HashMap peers = new HashMap();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					DarknetPeerNode pn = peerNodes[i];
					String peer_name = pn.getName();
					String peer_hash = "" + pn.hashCode();
					if(!peers.containsKey(peer_hash)) {
						peers.put(peer_hash, peer_name);
					}
				}
			}
			N2NTMToadlet.createN2NTMSendForm( pageNode, contentNode, ctx, peers);
			this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("update_notes")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("peerPrivateNote_"+peerNodes[i].hashCode())) {
					if(!request.getPartAsString("peerPrivateNote_"+peerNodes[i].hashCode(),250).equals(peerNodes[i].getPrivateDarknetCommentNote())) {
						peerNodes[i].setPrivateDarknetCommentNote(request.getPartAsString("peerPrivateNote_"+peerNodes[i].hashCode(),250));
					}
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("enable")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].enablePeer();
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("disable")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].disablePeer();
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_burst_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setBurstOnly(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_burst_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setBurstOnly(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_ignore_source_port")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setIgnoreSourcePort(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_ignore_source_port")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setIgnoreSourcePort(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_listen_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setListenOnly(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_listen_only")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setListenOnly(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("set_allow_local")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setAllowLocalAddresses(true);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("clear_allow_local")) {
			//int hashcode = Integer.decode(request.getParam("node")).intValue();
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					peerNodes[i].setAllowLocalAddresses(false);
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("remove") || (request.isPartSet("doAction") && request.getPartAsString("action",25).equals("remove"))) {			
			if(logMINOR) Logger.minor(this, "Remove node");
			
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {	
					if((peerNodes[i].timeLastConnectionCompleted() < (System.currentTimeMillis() - 1000*60*60*24*7) /* one week */) ||  (peerNodes[i].peerNodeStatus == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED) || request.isPartSet("forceit")){
						this.node.removeDarknetConnection(peerNodes[i]);
						if(logMINOR) Logger.minor(this, "Removed node: node_"+peerNodes[i].hashCode());
					}else{
						if(logMINOR) Logger.minor(this, "Refusing to remove : node_"+peerNodes[i].hashCode()+" (trying to prevent network churn) : let's display the warning message.");
						HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("confirmRemoveNodeTitle"), ctx);
						HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
						HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", l10n("confirmRemoveNodeWarningTitle")));
						HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
						content.addChild("p").addChild("#",
								L10n.getString("DarknetConnectionsToadlet.confirmRemoveNode", new String[] { "name" }, new String[] { peerNodes[i].getName() }));
						HTMLNode removeForm = ctx.addFormChild(content, "/friends/", "removeConfirmForm");
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "node_"+peerNodes[i].hashCode(), "remove" });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove", l10n("remove") });
						removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "forceit", l10n("forceRemove") });

						writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
						return; // FIXME: maybe it breaks multi-node removing
					}				
				} else {
					if(logMINOR) Logger.minor(this, "Part not set: node_"+peerNodes[i].hashCode());
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("acceptTransfer")) {
			// FIXME this is ugly, should probably move both this code and the PeerNode code somewhere.
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					long id = Long.parseLong(request.getPartAsString("id", 32)); // FIXME handle NumberFormatException
					peerNodes[i].acceptTransfer(id);
					break;
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else if (request.isPartSet("rejectTransfer")) {
			// FIXME this is ugly, should probably move both this code and the PeerNode code somewhere.
			DarknetPeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					long id = Long.parseLong(request.getPartAsString("id", 32)); // FIXME handle NumberFormatException
					peerNodes[i].rejectTransfer(id);
					break;
				}
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/friends/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		} else {
			this.handleGet(uri, new HTTPRequestImpl(uri), ctx);
		}
	}
	
	protected boolean hasNameColumn() {
		return true;
	}
	
	protected void drawNameColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus) {
		// name column
		peerRow.addChild("td", "class", "peer-name").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + peerNodeStatus.hashCode(), ((DarknetPeerNodeStatus)peerNodeStatus).getName());
	}

	protected boolean hasPrivateNoteColumn() {
		return true;
	}

	protected void drawPrivateNoteColumn(HTMLNode peerRow, PeerNodeStatus peerNodeStatus, boolean fProxyJavascriptEnabled) {
		// private darknet node comment note column
		DarknetPeerNodeStatus status = (DarknetPeerNodeStatus) peerNodeStatus;
		if(fProxyJavascriptEnabled) {
			peerRow.addChild("td", "class", "peer-private-darknet-comment-note").addChild("input", new String[] { "type", "name", "size", "maxlength", "onBlur", "onChange", "value" }, new String[] { "text", "peerPrivateNote_" + peerNodeStatus.hashCode(), "16", "250", "peerNoteBlur();", "peerNoteChange();", status.getPrivateDarknetCommentNote() });
		} else {
			peerRow.addChild("td", "class", "peer-private-darknet-comment-note").addChild("input", new String[] { "type", "name", "size", "maxlength", "value" }, new String[] { "text", "peerPrivateNote_" + peerNodeStatus.hashCode(), "16", "250", status.getPrivateDarknetCommentNote() });
		}
	}

	protected SimpleFieldSet getNoderef() {
		return node.exportDarknetPublicFieldSet();
	}

	protected PeerNodeStatus[] getPeerNodeStatuses() {
		return node.peers.getDarknetPeerNodeStatuses();
	}

	protected String getPageTitle(String titleCountString, String myName) {
		return L10n.getString("DarknetConnectionsToadlet.fullTitle", new String[] { "counts", "name" }, new String[] { titleCountString, node.getMyName() } );
	}
	
	protected void drawAddPeerBox(HTMLNode contentNode, ToadletContext ctx) {
		// BEGIN PEER ADDITION BOX
		HTMLNode peerAdditionInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		peerAdditionInfobox.addChild("div", "class", "infobox-header", l10n("addPeerTitle"));
		HTMLNode peerAdditionContent = peerAdditionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerAdditionForm = ctx.addFormChild(peerAdditionContent, ".", "addPeerForm");
		peerAdditionForm.addChild("#", l10n("pasteReference"));
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("textarea", new String[] { "id", "name", "rows", "cols" }, new String[] { "reftext", "ref", "8", "74" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", (l10n("urlReference") + ' '));
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name" }, new String[] { "refurl", "text", "url" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", (l10n("fileReference") + ' '));
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name" }, new String[] { "reffile", "file", "reffile" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("#", (l10n("enterDescription") + ' '));
		peerAdditionForm.addChild("input", new String[] { "id", "type", "name", "size", "maxlength", "value" }, new String[] { "peerPrivateNote", "text", "peerPrivateNote", "16", "250", "" });
		peerAdditionForm.addChild("br");
		peerAdditionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", l10n("add") });
	}

	protected boolean shouldDrawNoderefBox(boolean advancedModeEnabled) {
		return true;
	}

	protected boolean showPeerActionsBox() {
		return true;
	}

	protected void drawPeerActionSelectBox(HTMLNode peerForm, boolean advancedModeEnabled) {
		HTMLNode actionSelect = peerForm.addChild("select", new String[] { "id", "name" }, new String[] { "action", "action" });
		actionSelect.addChild("option", "value", "", l10n("selectAction"));
		actionSelect.addChild("option", "value", "send_n2ntm", l10n("sendMessageToPeers"));
		actionSelect.addChild("option", "value", "update_notes", l10n("updateChangedPrivnotes"));
		if(advancedModeEnabled) {
			actionSelect.addChild("option", "value", "enable", "Enable selected peers");
			actionSelect.addChild("option", "value", "disable", "Disable selected peers");
			actionSelect.addChild("option", "value", "set_burst_only", "On selected peers, set BurstOnly (only set this if you have a static IP and are not NATed and neither is the peer)");
			actionSelect.addChild("option", "value", "clear_burst_only", "On selected peers, clear BurstOnly");
			actionSelect.addChild("option", "value", "set_listen_only", "On selected peers, set ListenOnly (not recommended)");
			actionSelect.addChild("option", "value", "clear_listen_only", "On selected peers, clear ListenOnly");
			actionSelect.addChild("option", "value", "set_allow_local", "On selected peers, set allowLocalAddresses (useful if you are connecting to another node on the same LAN)");
			actionSelect.addChild("option", "value", "clear_allow_local", "On selected peers, clear allowLocalAddresses");
			actionSelect.addChild("option", "value", "set_ignore_source_port", "On selected peers, set ignoreSourcePort (try this if behind an evil corporate firewall; otherwise not recommended)");
			actionSelect.addChild("option", "value", "clear_ignore_source_port", "On selected peers, clear ignoreSourcePort");
		}
		actionSelect.addChild("option", "value", "", l10n("separator"));
		actionSelect.addChild("option", "value", "remove", l10n("removePeers"));
		peerForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "doAction", l10n("go") });
	}

	protected String getPeerListTitle() {
		return l10n("myFriends");
	}

}
