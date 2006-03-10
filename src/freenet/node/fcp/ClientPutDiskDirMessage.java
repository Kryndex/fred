package freenet.node.fcp;

import java.io.File;
import java.util.HashMap;

import freenet.client.async.ManifestElement;
import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

/**
 * Insert a directory from disk as a manifest.
 * 
 * ClientPutDiskDirMessage
 * < generic fields from ClientPutDirMessage >
 * Filename=<filename>
 * AllowUnreadableFiles=<unless true, any unreadable files cause the whole request to fail>
 * End
 */
public class ClientPutDiskDirMessage extends ClientPutDirMessage {

	public static String name = "ClientPutDiskDir";
	
	final File dirname;
	final boolean allowUnreadableFiles;

	public ClientPutDiskDirMessage(SimpleFieldSet fs) throws MessageInvalidException {
		super(fs);
		allowUnreadableFiles = Fields.stringToBool(fs.get("AllowUnreadableFiles"), false);
		String fnam = fs.get("Filename");
		if(fnam == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Filename missing", identifier);
		dirname = new File(fnam);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		// Create a directory listing of Buckets of data, mapped to ManifestElement's.
		// Directories are sub-HashMap's.
		HashMap buckets = makeBucketsByName(dirname);
		handler.startClientPutDir(this, buckets);
	}

    /**
     * Create a map of String -> Bucket for every file in a directory
     * and its subdirs.
     * @throws MessageInvalidException 
     */
    private HashMap makeBucketsByName(File thisdir) throws MessageInvalidException {
    	
    	Logger.minor(this, "Listing directory: "+thisdir);
    	
    	HashMap ret = new HashMap();
    	
    	File filelist[] = thisdir.listFiles();
    	if(filelist == null)
    		throw new IllegalArgumentException("No such directory");
    	for(int i = 0 ; i < filelist.length ; i++) {
                //   Skip unreadable files and dirs
		//   Skip files nonexistant (dangling symlinks) - check last 
	        if (filelist[i].canRead() && filelist[i].exists()) {
	        	if (filelist[i].isFile()) {
	        		File f = filelist[i];
	        		
	        		FileBucket bucket = new FileBucket(f, true, false, false, false);
	        		
	        		ret.put(f.getName(), new ManifestElement(f.getName(), bucket, null));
	        	} else if(filelist[i].isDirectory()) {
	        		HashMap subdir = makeBucketsByName(new File(thisdir, filelist[i].getName()));
	        		ret.put(filelist[i].getName(), subdir);
	        	} else if(!allowUnreadableFiles) {
	        		throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "Not directory and not file: "+filelist[i], identifier);
	        	}
	        } else {
	        	throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "Not readable or doesn't exist: "+filelist[i], identifier);
	        }
    	}
    	return ret;
	}

}
