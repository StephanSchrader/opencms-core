/*
 * File   : $Source: /alkacon/cvs/opencms/src/org/opencms/importexport/CmsExport.java,v $
 * Date   : $Date: 2005/04/30 11:15:38 $
 * Version: $Revision: 1.60 $
 *
 * This library is part of OpenCms -
 * the Open Source Content Mananagement System
 *
 * Copyright (C) 2002 - 2005 Alkacon Software (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.importexport;

import org.opencms.file.CmsFile;
import org.opencms.file.CmsFolder;
import org.opencms.file.CmsGroup;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsProperty;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.file.CmsUser;
import org.opencms.file.CmsVfsException;
import org.opencms.i18n.CmsMessageContainer;
import org.opencms.loader.CmsLoaderException;
import org.opencms.main.CmsEvent;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.I_CmsConstants;
import org.opencms.main.I_CmsEventListener;
import org.opencms.main.OpenCms;
import org.opencms.report.CmsShellReport;
import org.opencms.report.I_CmsReport;
import org.opencms.security.CmsAccessControlEntry;
import org.opencms.security.CmsRole;
import org.opencms.security.CmsRoleViolationException;
import org.opencms.security.I_CmsPrincipal;
import org.opencms.util.CmsDateUtil;
import org.opencms.util.CmsUUID;
import org.opencms.util.CmsXmlSaxWriter;
import org.opencms.workplace.I_CmsWpConstants;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.SAXWriter;
import org.xml.sax.SAXException;

/**
 * Provides the functionality to export files from the OpenCms VFS to a ZIP file.<p>
 * 
 * The ZIP file written will contain a copy of all exported files with their contents.
 * It will also contain a <code>manifest.xml</code> file in wich all meta-information 
 * about this files are stored, like permissions etc.
 *
 * @author Alexander Kandzior (a.kandzior@alkacon.com)
 * @author Michael Emmerich (m.emmerich@alkacon.com)
 * 
 * @version $Revision: 1.60 $ $Date: 2005/04/30 11:15:38 $
 */
public class CmsExport implements Serializable {
    
    /** The log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsExport.class);

    private static final int C_SUB_LENGTH = 4096;

    /** The CmsObject to do the operations. */
    private CmsObject m_cms;

    /** Max file age of contents to export. */
    private long m_contentAge;

    /** Indicates if the system should be included to the export. */
    private boolean m_excludeSystem;

    /** Indicates if the unchanged resources should be included to the export .*/
    private boolean m_excludeUnchanged;

    /** Counter for the export. */
    private int m_exportCount;

    /** Set of all exported pages, required for later page body file export. */
    private Set m_exportedPageFiles;

    /** Set of all exported files, required for later page body file export. */
    private Set m_exportedResources;

    /** The export ZIP file to store resources in. */
    private String m_exportFileName;

    /** Indicates if the user data and group data should be included to the export. */
    private boolean m_exportUserdata;

    /** The export ZIP stream to write resources to. */
    private ZipOutputStream m_exportZipStream;

    /** The report for the log messages. */
    private I_CmsReport m_report;

    /** The top level file node where all resources are appended to. */
    private Element m_resourceNode;

    /** The SAX writer to write the output to. */
    private SAXWriter m_saxWriter;

    /** Cache for previously added super folders. */
    private Vector m_superFolders;

    /**
     * Constructs a new uninitialized export, required for special subclass data export.<p>
     */
    public CmsExport() {

        // empty constructor
    }

    /**
     * Constructs a new export.<p>
     *
     * @param cms the cmsObject to work with
     * @param exportFile the file or folder to export to
     * @param resourcesToExport the paths of folders and files to export
     * @param excludeSystem if true, the system folder is excluded, if false all the resources in
     *        resourcesToExport are included
     * @param excludeUnchanged <code>true</code>, if unchanged files should be excluded
     * @throws CmsImportExportException if something goes wrong
     * @throws CmsRoleViolationException if the current user has not the required role
     */
    public CmsExport(
        CmsObject cms,
        String exportFile,
        String[] resourcesToExport,
        boolean excludeSystem,
        boolean excludeUnchanged)
    throws CmsImportExportException, CmsRoleViolationException {

        this(cms, exportFile, resourcesToExport, excludeSystem, excludeUnchanged, null, false, 0, new CmsShellReport());
    }

    /**
     * Constructs a new export.<p>
     *
     * @param cms the cmsObject to work with
     * @param exportFile the file or folder to export to
     * @param resourcesToExport the paths of folders and files to export
     * @param excludeSystem if true, the system folder is excluded, if false all the resources in
     *        resourcesToExport are included
     * @param excludeUnchanged <code>true</code>, if unchanged files should be excluded
     * @param moduleElement module informations in a Node for module export
     * @param exportUserdata if true, the user and grou pdata will also be exported
     * @param contentAge export contents changed after this date/time
     * @param report to handle the log messages
     * 
     * @throws CmsImportExportException if something goes wrong
     * @throws CmsRoleViolationException if the current user has not the required role
     */
    public CmsExport(
        CmsObject cms,
        String exportFile,
        String[] resourcesToExport,
        boolean excludeSystem,
        boolean excludeUnchanged,
        Element moduleElement,
        boolean exportUserdata,
        long contentAge,
        I_CmsReport report)
    throws CmsImportExportException, CmsRoleViolationException {

        setCms(cms);
        setReport(report);
        setExportFileName(exportFile);

        // check if the user has the required permissions
        cms.checkRole(CmsRole.EXPORT_DATABASE);

        m_excludeSystem = excludeSystem;
        m_excludeUnchanged = excludeUnchanged;
        m_exportUserdata = exportUserdata;
        m_contentAge = contentAge;
        m_exportCount = 0;

        // clear all caches
        report.println(report.key("report.clearcache"), I_CmsReport.C_FORMAT_NOTE);
        OpenCms.fireCmsEvent(new CmsEvent(I_CmsEventListener.EVENT_CLEAR_CACHES, Collections.EMPTY_MAP));

        try {
            Element exportNode = openExportFile();

            if (moduleElement != null) {
                // add the module element
                exportNode.add(moduleElement);
                // write the XML
                digestElement(exportNode, moduleElement);
            }

            exportAllResources(exportNode, resourcesToExport);

            // export userdata and groupdata if selected
            if (m_exportUserdata) {
                Element userGroupData = exportNode.addElement(I_CmsConstants.C_EXPORT_TAG_USERGROUPDATA);
                getSaxWriter().writeOpen(userGroupData);

                exportGroups(userGroupData);
                exportUsers(userGroupData);

                getSaxWriter().writeClose(userGroupData);
                exportNode.remove(userGroupData);
            }

            closeExportFile(exportNode);
        } catch (SAXException se) {
            getReport().println(se);
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_EXPORTING_TO_FILE_1, getExportFileName());
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, se);
            }
            
            throw new CmsImportExportException(message, se);
        } catch (IOException ioe) {
            getReport().println(ioe);
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_EXPORTING_TO_FILE_1, getExportFileName());
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, ioe);
            }
            
            throw new CmsImportExportException(message, ioe);
        }
    }

    /** 
     * Checks whether some of the resources are redundant because a superfolder has also
     * been selected or a file is included in a folder.<p>
     * 
     * @param folderNames contains the full pathnames of all folders
     * @param fileNames contains the full pathnames of all files
     */
    public static void checkRedundancies(Vector folderNames, Vector fileNames) {

        int i, j;
        if (folderNames == null) {
            return;
        }
        Vector redundant = new Vector();
        int n = folderNames.size();
        if (n > 1) {
            // otherwise no check needed, because there is only one resource

            for (i = 0; i < n; i++) {
                redundant.addElement(new Boolean(false));
            }
            for (i = 0; i < n - 1; i++) {
                for (j = i + 1; j < n; j++) {
                    if (((String)folderNames.elementAt(i)).length() < ((String)folderNames.elementAt(j)).length()) {
                        if (((String)folderNames.elementAt(j)).startsWith((String)folderNames.elementAt(i))) {
                            redundant.setElementAt(new Boolean(true), j);
                        }
                    } else {
                        if (((String)folderNames.elementAt(i)).startsWith((String)folderNames.elementAt(j))) {
                            redundant.setElementAt(new Boolean(true), i);
                        }
                    }
                }
            }
            for (i = n - 1; i >= 0; i--) {
                if (((Boolean)redundant.elementAt(i)).booleanValue()) {
                    folderNames.removeElementAt(i);
                }
            }
        }
        // now remove the files who are included automatically in a folder
        // otherwise there would be a zip exception

        for (i = fileNames.size() - 1; i >= 0; i--) {
            for (j = 0; j < folderNames.size(); j++) {
                if (((String)fileNames.elementAt(i)).startsWith((String)folderNames.elementAt(j))) {
                    fileNames.removeElementAt(i);
                }
            }
        }
    }

    /**
     * Exports the given folder and all child resources.<p>
     *
     * @param folderName to complete path to the resource to export
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     * @throws IOException if not all resources could be appended to the ZIP archive
     */
    protected void addChildResources(String folderName) throws CmsImportExportException, IOException, SAXException {

        try {
            // get all subFolders
            List subFolders = getCms().getSubFolders(folderName, CmsResourceFilter.IGNORE_EXPIRATION);
            // get all files in folder
            List subFiles = getCms().getFilesInFolder(folderName, CmsResourceFilter.IGNORE_EXPIRATION);
    
            // walk through all files and export them
            for (int i = 0; i < subFiles.size(); i++) {
                CmsResource file = (CmsResource)subFiles.get(i);
                int state = file.getState();
                long age = file.getDateLastModified();
    
                if (getCms().getRequestContext().currentProject().isOnlineProject()
                    || (!m_excludeUnchanged)
                    || state == I_CmsConstants.C_STATE_NEW
                    || state == I_CmsConstants.C_STATE_CHANGED) {
                    if ((state != I_CmsConstants.C_STATE_DELETED)
                        && (!file.getName().startsWith("~"))
                        && (age >= m_contentAge)) {
                        exportFile(getCms().readFile(getCms().getSitePath(file), CmsResourceFilter.IGNORE_EXPIRATION));
                    }
                }
                // release file header memory
                subFiles.set(i, null);
            }
            // all files are exported, release memory
            subFiles = null;
    
            // walk through all subfolders and export them
            for (int i = 0; i < subFolders.size(); i++) {
                CmsResource folder = (CmsResource)subFolders.get(i);
                if (folder.getState() != I_CmsConstants.C_STATE_DELETED) {
                    // check if this is a system-folder and if it should be included.
                    String export = getCms().getSitePath(folder);
                    if (// always export "/system/"
                    export.equalsIgnoreCase(I_CmsWpConstants.C_VFS_PATH_SYSTEM) // OR always export "/system/bodies/"                                  
                        || export.startsWith(I_CmsWpConstants.C_VFS_PATH_BODIES) // OR always export "/system/galleries/"
                        || export.startsWith(I_CmsWpConstants.C_VFS_PATH_GALLERIES) // OR option "exclude system folder" selected
                        || !(m_excludeSystem // AND export folder is a system folder
                        && export.startsWith(I_CmsWpConstants.C_VFS_PATH_SYSTEM))) {
    
                        // export this folder only if age is above selected age
                        // default for selected age (if not set by user) is <code>long 0</code> (i.e. 1970)
                        if (folder.getDateLastModified() >= m_contentAge) {
                            // only export folder data to manifest.xml if it has changed
                            appendResourceToManifest(folder, false);
                        }
    
                        // export all sub-resources in this folder
                        addChildResources(getCms().getSitePath(folder));
                    }
                }
                // release folder memory
                subFolders.set(i, null);
            }
        } catch (CmsImportExportException e) {
            
            throw e;
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_ADDING_CHILD_RESOURCES_1, folderName);
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Closes the export ZIP file and saves the XML document for the manifest.<p>
     * 
     * @param exportNode the export root node
     * @throws SAXException if something goes wrong procesing the manifest.xml
     * @throws IOException if something goes wrong while closing the export file
     */
    protected void closeExportFile(Element exportNode) throws IOException, SAXException {

        // close the <export> Tag
        getSaxWriter().writeClose(exportNode);

        // close the XML document 
        CmsXmlSaxWriter xmlSaxWriter = (CmsXmlSaxWriter)getSaxWriter().getContentHandler();
        xmlSaxWriter.endDocument();

        // create zip entry for the manifest XML document
        ZipEntry entry = new ZipEntry(I_CmsConstants.C_EXPORT_XMLFILENAME);
        getExportZipStream().putNextEntry(entry);

        // complex substring operation is required to ensure handling for very large export manifest files
        StringBuffer result = ((StringWriter)xmlSaxWriter.getWriter()).getBuffer();
        int steps = result.length() / C_SUB_LENGTH;
        int rest = result.length() % C_SUB_LENGTH;
        int pos = 0;
        for (int i = 0; i < steps; i++) {
            String sub = result.substring(pos, pos + C_SUB_LENGTH);
            getExportZipStream().write(sub.getBytes(OpenCms.getSystemInfo().getDefaultEncoding()));
            pos += C_SUB_LENGTH;
        }
        if (rest > 0) {
            String sub = result.substring(pos, pos + rest);
            getExportZipStream().write(sub.getBytes(OpenCms.getSystemInfo().getDefaultEncoding()));
        }

        // close the zip entry for the manifest XML document
        getExportZipStream().closeEntry();

        // finally close the zip stream
        getExportZipStream().close();
    }

    /**
     * Writes the output element to the XML output writer and detaches it 
     * from it's parent element.<p> 
     * 
     * @param parent the parent element
     * @param output the output element 
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    protected void digestElement(Element parent, Element output) throws SAXException {

        m_saxWriter.write(output);
        parent.remove(output);
    }

    /**
     * Exports all resources and possible sub-folders form the provided list of resources.
     * 
     * @param parent the parent node to add the resources to
     * @param resourcesToExport the list of resources to export
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     * @throws IOException if not all resources could be appended to the ZIP archive
     */
    protected void exportAllResources(Element parent, String[] resourcesToExport) throws CmsImportExportException, IOException, SAXException {

        // export all the resources
        String resourceNodeName = getResourceNodeName();
        m_resourceNode = parent.addElement(resourceNodeName);
        getSaxWriter().writeOpen(m_resourceNode);

        // distinguish folder and file names   
        Vector folderNames = new Vector();
        Vector fileNames = new Vector();
        for (int i = 0; i < resourcesToExport.length; i++) {
            if (resourcesToExport[i].endsWith(I_CmsConstants.C_FOLDER_SEPARATOR)) {
                folderNames.addElement(resourcesToExport[i]);
            } else {
                fileNames.addElement(resourcesToExport[i]);
            }
        }

        // remove the possible redundancies in the list of resources
        checkRedundancies(folderNames, fileNames);

        // init sets required for the body file exports 
        m_exportedResources = new HashSet();
        m_exportedPageFiles = new HashSet();

        // export the folders
        for (int i = 0; i < folderNames.size(); i++) {
            String path = (String)folderNames.elementAt(i);
            // first add superfolders to the xml-config file
            addParentFolders(path);
            addChildResources(path);
            m_exportedResources.add(path);
        }
        // export the files
        addFiles(fileNames);
        // export all body files that have not already been exported
        addPageBodyFiles();

        // write the XML
        getSaxWriter().writeClose(m_resourceNode);
        parent.remove(m_resourceNode);
        m_resourceNode = null;
    }

    /**
     * Returns the OpenCms context object this export was initialized with.<p>
     * 
     * @return the OpenCms context object this export was initialized with
     */
    protected CmsObject getCms() {

        return m_cms;
    }

    /**
     * Returns the name of the export file.<p>
     * 
     * @return the name of the export file
     */
    protected String getExportFileName() {

        return m_exportFileName;
    }

    /**
     * Returns the name of the main export node.<p>
     * 
     * @return the name of the main export node
     */
    protected String getExportNodeName() {

        return I_CmsConstants.C_EXPORT_TAG_EXPORT;
    }

    /**
     * Returns the zip output stream to write to.<p>
     * 
     * @return the zip output stream to write to
     */
    protected ZipOutputStream getExportZipStream() {

        return m_exportZipStream;
    }

    /**
     * Returns the report to write progess messages to.<p>
     * 
     * @return the report to write progess messages to
     */
    protected I_CmsReport getReport() {

        return m_report;
    }

    /**
     * Returns the name for the main resource node.<p>
     * 
     * @return the name for the main resource node
     */
    protected String getResourceNodeName() {

        return "files";
    }

    /**
     * Returns the SAX baesed xml writer to write the XML output to.<p>
     * 
     * @return the SAX baesed xml writer to write the XML output to
     */
    protected SAXWriter getSaxWriter() {

        return m_saxWriter;
    }

    /**
     * Checks if a property should be written to the export or not.<p>
     * 
     * @param property the property to check
     * @return if true, the property is to be ignored, otherwise it should be exported
     */
    protected boolean isIgnoredProperty(CmsProperty property) {

        if (property == null) {
            return true;
        }
        // default implementation is to export all properties not null
        return false;
    }

    /**
     * Opens the export ZIP file and initializes the internal XML document for the manifest.<p>
     * 
     * @return the node in the XML document where all files are appended to
     * @throws SAXException if something goes wrong procesing the manifest.xml
     * @throws IOException if something goes wrong while closing the export file
     */
    protected Element openExportFile() throws IOException, SAXException {

        // create the export-zipstream
        setExportZipStream(new ZipOutputStream(new FileOutputStream(getExportFileName())));
        // generate the SAX XML writer
        CmsXmlSaxWriter saxHandler = new CmsXmlSaxWriter(
            new StringWriter(4096),
            OpenCms.getSystemInfo().getDefaultEncoding());
        // initialize the dom4j writer object as member variable
        setSaxWriter(new SAXWriter(saxHandler, saxHandler));
        // the XML document to write the XMl to
        Document doc = DocumentHelper.createDocument();
        // start the document
        saxHandler.startDocument();

        // the node in the XML document where the file entries are appended to        
        String exportNodeName = getExportNodeName();
        // add main export node to XML document
        Element exportNode = doc.addElement(exportNodeName);
        getSaxWriter().writeOpen(exportNode);

        // add the info element. it contains all infos for this export
        Element info = exportNode.addElement(I_CmsConstants.C_EXPORT_TAG_INFO);
        info.addElement(I_CmsConstants.C_EXPORT_TAG_CREATOR).addText(
            getCms().getRequestContext().currentUser().getName());
        info.addElement(I_CmsConstants.C_EXPORT_TAG_OC_VERSION).addText(OpenCms.getSystemInfo().getVersionName());
        info.addElement(I_CmsConstants.C_EXPORT_TAG_DATE).addText(
            CmsDateUtil.getDateTimeShort(System.currentTimeMillis()));
        info.addElement(I_CmsConstants.C_EXPORT_TAG_PROJECT).addText(
            getCms().getRequestContext().currentProject().getName());
        info.addElement(I_CmsConstants.C_EXPORT_TAG_VERSION).addText(I_CmsConstants.C_EXPORT_VERSION);

        // write the XML
        digestElement(exportNode, info);

        return exportNode;
    }

    /**
     * Sets the OpenCms context object this export was initialized with.<p>
     * 
     * @param cms the OpenCms context object this export was initialized with
     */
    protected void setCms(CmsObject cms) {

        m_cms = cms;
    }

    /**
     * Sets the name of the export file.<p>
     * 
     * @param exportFileName the name of the export file
     */
    protected void setExportFileName(String exportFileName) {

        // ensure the export file name ends with ".zip"
        if (!exportFileName.toLowerCase().endsWith(".zip")) {
            m_exportFileName = exportFileName + ".zip";
        } else {
            m_exportFileName = exportFileName;
        }
    }

    /**
     * Sets the zip output stream to write to.<p>
     * 
     * @param exportZipStream the zip output stream to write to
     */
    protected void setExportZipStream(ZipOutputStream exportZipStream) {

        m_exportZipStream = exportZipStream;
    }

    /**
     * Sets the report to write progess messages to.<p>
     * 
     * @param report the report to write progess messages to
     */
    protected void setReport(I_CmsReport report) {

        m_report = report;
    }

    /**
     * Sets the SAX baesed xml writer to write the XML output to.<p>
     * 
     * @param saxWriter the SAX baesed xml writer to write the XML output to
     */
    protected void setSaxWriter(SAXWriter saxWriter) {

        m_saxWriter = saxWriter;
    }

    /**
     * Adds all files in fileNames to the manifest.xml file.<p>
     * 
     * @param fileNames Vector of path Strings, e.g. <code>/folder/index.html</code>
     * @throws CmsImportExportException2 if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void addFiles(Vector fileNames) throws CmsImportExportException, IOException, SAXException {

        if (fileNames != null) {
            for (int i = 0; i < fileNames.size(); i++) {
                String fileName = (String)fileNames.elementAt(i);
                try {
                    CmsFile file = getCms().readFile(fileName, CmsResourceFilter.IGNORE_EXPIRATION);
                    if ((file.getState() != I_CmsConstants.C_STATE_DELETED) && (!file.getName().startsWith("~"))) {
                        addParentFolders(fileName);
                        exportFile(file);
                    }
                } catch (CmsImportExportException e) {
                    
                    throw e;
                } catch (CmsException e) {
                    if (e.getType() != CmsVfsException.C_VFS_RESOURCE_DELETED) {

                        CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_ADDING_FILE_1, fileName);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(message, e);
                        }
                        
                        throw new CmsImportExportException(message, e);
                    }
                }
            }
        }
    }

    /**
     * Exports all page body files that have not explicityl been added by the user.<p>
     * 
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void addPageBodyFiles() throws CmsImportExportException, IOException, SAXException {

        Iterator i;

        Vector bodyFileNames = new Vector();
        String bodyPath = I_CmsWpConstants.C_VFS_PATH_BODIES.substring(
            0,
            I_CmsWpConstants.C_VFS_PATH_BODIES.lastIndexOf("/"));

        // check all exported page files if their body has already been exported
        i = m_exportedPageFiles.iterator();
        while (i.hasNext()) {
            String filename = (String)i.next();
            // check if the site path is within the filename. If so,this export is
            // started from the root site and the path to the bodies must be modifed
            // this is not nice, but it works.
            if (filename.startsWith(I_CmsConstants.VFS_FOLDER_SITES)) {
                filename = filename.substring(I_CmsConstants.VFS_FOLDER_SITES.length() + 1, filename.length());
                filename = filename.substring(filename.indexOf("/"), filename.length());
            }
            String body = bodyPath + filename;
            bodyFileNames.add(body);
        }

        // now export the body files that have not already been exported
        addFiles(bodyFileNames);
    }

    /**
     * Adds the parent folders of the given resource to the config file, 
     * starting at the top, excluding the root folder.<p>
     * 
     * @param resourceName the name of a resource in the VFS
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void addParentFolders(String resourceName) throws CmsImportExportException, SAXException {

        try {
            // Initialize the "previously added folder cache"
            if (m_superFolders == null) {
                m_superFolders = new Vector();
            }
            Vector superFolders = new Vector();
    
            // Check, if the path is really a folder
            if (resourceName.lastIndexOf(I_CmsConstants.C_ROOT) != (resourceName.length() - 1)) {
                resourceName = resourceName.substring(0, resourceName.lastIndexOf(I_CmsConstants.C_ROOT) + 1);
            }
            while (resourceName.length() > I_CmsConstants.C_ROOT.length()) {
                superFolders.addElement(resourceName);
                resourceName = resourceName.substring(0, resourceName.length() - 1);
                resourceName = resourceName.substring(0, resourceName.lastIndexOf(I_CmsConstants.C_ROOT) + 1);
            }
            for (int i = superFolders.size() - 1; i >= 0; i--) {
                String addFolder = (String)superFolders.elementAt(i);
                if (!m_superFolders.contains(addFolder)) {
                    // This super folder was NOT added previously. Add it now!
                    CmsFolder folder = getCms().readFolder(addFolder, CmsResourceFilter.IGNORE_EXPIRATION);
                    appendResourceToManifest(folder, false);
                    // Remember that this folder was added
                    m_superFolders.addElement(addFolder);
                }
            }
        } catch (CmsImportExportException e) {
            
            throw e;
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_ADDING_PARENT_FOLDERS_1, resourceName);
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Writes the data for a resource (like access-rights) to the <code>manifest.xml</code> file.<p>
     * 
     * @param resource the resource to get the data from
     * @param source flag to show if the source information in the xml file must be written
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void appendResourceToManifest(CmsResource resource, boolean source) throws CmsImportExportException, SAXException {

        try {
            CmsProperty property = null;
            String key = null, value = null;
            Element propertyElement = null;
    
            // define the file node
            Element fileElement = m_resourceNode.addElement(I_CmsConstants.C_EXPORT_TAG_FILE);
    
            // only write <source> if resource is a file
            String fileName = trimResourceName(getCms().getSitePath(resource));
            if (resource.isFile()) {
                if (source) {
                    fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_SOURCE).addText(fileName);
                }
            } else {
                // output something to the report for the folder
                getReport().print(" ( " + ++m_exportCount + " ) ", I_CmsReport.C_FORMAT_NOTE);
                getReport().print(getReport().key("report.exporting"), I_CmsReport.C_FORMAT_NOTE);
                getReport().print(getCms().getSitePath(resource));
                getReport().print(getReport().key("report.dots"));
                getReport().println(getReport().key("report.ok"), I_CmsReport.C_FORMAT_OK);
    
                if (OpenCms.getLog(this).isInfoEnabled()) {
                    OpenCms.getLog(this).info(
                        "( "
                            + m_exportCount
                            + " ) "
                            + m_report.key("report.exporting")
                            + getCms().getSitePath(resource)
                            + m_report.key("report.dots")
                            + m_report.key("report.ok"));
                }
            }
    
            // <destination>
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_DESTINATION).addText(fileName);
            // <type>
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_TYPE).addText(
                OpenCms.getResourceManager().getResourceType(resource.getTypeId()).getTypeName());
    
            if (resource.isFile()) {
                //  <uuidresource>
                fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_UUIDRESOURCE).addText(
                    resource.getResourceId().toString());
            }
    
            // <datelastmodified>
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_DATELASTMODIFIED).addText(
                CmsDateUtil.getHeaderDate(resource.getDateLastModified()));
            // <userlastmodified>
            String userNameLastModified = null;
            try {
                userNameLastModified = getCms().readUser(resource.getUserLastModified()).getName();
            } catch (CmsException e) {
                userNameLastModified = OpenCms.getDefaultUsers().getUserAdmin();
            }
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_USERLASTMODIFIED).addText(userNameLastModified);
            // <datecreated>
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_DATECREATED).addText(
                CmsDateUtil.getHeaderDate(resource.getDateCreated()));
            // <usercreated>
            String userNameCreated = null;
            try {
                userNameCreated = getCms().readUser(resource.getUserCreated()).getName();
            } catch (CmsException e) {
                userNameCreated = OpenCms.getDefaultUsers().getUserAdmin();
            }
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_USERCREATED).addText(userNameCreated);
            // <release>
            if (resource.getDateReleased() != CmsResource.DATE_RELEASED_DEFAULT) {
                fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_DATERELEASED).addText(
                    CmsDateUtil.getHeaderDate(resource.getDateReleased()));
            }
            // <expire>
            if (resource.getDateExpired() != CmsResource.DATE_EXPIRED_DEFAULT) {
                fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_DATEEXPIRED).addText(
                    CmsDateUtil.getHeaderDate(resource.getDateExpired()));
            }
            // <flags>
            int resFlags = resource.getFlags();
            resFlags &= ~I_CmsConstants.C_RESOURCEFLAG_LABELLINK;
            fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_FLAGS).addText(Integer.toString(resFlags));
    
            // write the properties to the manifest
            Element propertiesElement = fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_PROPERTIES);
            List properties = getCms().readPropertyObjects(getCms().getSitePath(resource), false);
            for (int i = 0, n = properties.size(); i < n; i++) {
                property = (CmsProperty)properties.get(i);
    
                if (isIgnoredProperty(property)) {
                    continue;
                }
    
                key = property.getName();
    
                for (int j = 0; j < 2; j++) {
                    // iterations made here:
                    // 0) append individual/structure property value
                    // 1) append shared/resource property value
                    if ((j == 0 && (value = property.getStructureValue()) != null)
                        || (j == 1 && (value = property.getResourceValue()) != null)) {
                        propertyElement = propertiesElement.addElement(I_CmsConstants.C_EXPORT_TAG_PROPERTY);
    
                        if (j == 1) {
                            // add a type attrib. to the property node in case of a shared/resource property value
                            propertyElement.addAttribute(
                                I_CmsConstants.C_EXPORT_TAG_PROPERTY_ATTRIB_TYPE,
                                I_CmsConstants.C_EXPORT_TAG_PROPERTY_ATTRIB_TYPE_SHARED);
                        }
    
                        propertyElement.addElement(I_CmsConstants.C_EXPORT_TAG_NAME).addText(key);
                        propertyElement.addElement(I_CmsConstants.C_EXPORT_TAG_VALUE).addCDATA(value);
                    }
                }
            }
    
            // append the nodes for access control entries
            Element acl = fileElement.addElement(I_CmsConstants.C_EXPORT_TAG_ACCESSCONTROL_ENTRIES);
    
            // read the access control entries
            List fileAcEntries = getCms().getAccessControlEntries(getCms().getSitePath(resource), false);
            Iterator i = fileAcEntries.iterator();
    
            // create xml elements for each access control entry
            while (i.hasNext()) {
                CmsAccessControlEntry ace = (CmsAccessControlEntry)i.next();
                Element a = acl.addElement(I_CmsConstants.C_EXPORT_TAG_ACCESSCONTROL_ENTRY);
    
                // now check if the principal is a group or a user
                int flags = ace.getFlags();
                String acePrincipalName = "";
                CmsUUID acePrincipal = ace.getPrincipal();
                if ((flags & I_CmsConstants.C_ACCESSFLAGS_GROUP) > 0) {
                    // the principal is a group
                    acePrincipalName = I_CmsPrincipal.C_PRINCIPAL_GROUP + '.' + getCms().readGroup(acePrincipal).getName();
                } else {
                    // the principal is a user
                    acePrincipalName = I_CmsPrincipal.C_PRINCIPAL_USER + '.' + getCms().readUser(acePrincipal).getName();
                }
    
                a.addElement(I_CmsConstants.C_EXPORT_TAG_ACCESSCONTROL_PRINCIPAL).addText(acePrincipalName);
                a.addElement(I_CmsConstants.C_EXPORT_TAG_FLAGS).addText(Integer.toString(flags));
    
                Element b = a.addElement(I_CmsConstants.C_EXPORT_TAG_ACCESSCONTROL_PERMISSIONSET);
                b.addElement(I_CmsConstants.C_EXPORT_TAG_ACCESSCONTROL_ALLOWEDPERMISSIONS).addText(
                    Integer.toString(ace.getAllowedPermissions()));
                b.addElement(I_CmsConstants.C_EXPORT_TAG_ACCESSCONTROL_DENIEDPERMISSIONS).addText(
                    Integer.toString(ace.getDeniedPermissions()));
            }
    
            // write the XML
            digestElement(m_resourceNode, fileElement);
        } catch (CmsImportExportException e) {
            
            throw e;
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_APPENDING_RESOURCE_TO_MANIFEST_1, resource.getRootPath());
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Exports one single file with all its data and content.<p>
     *
     * @param file the file to be exported
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     * @throws CmsLoaderException if an "old style" XML page could be exported
     * @throws IOException if the ZIP entry for the file could be appended to the ZIP archive
     */
    private void exportFile(CmsFile file) throws CmsImportExportException, SAXException, CmsLoaderException, IOException {

        String source = trimResourceName(getCms().getSitePath(file));
        getReport().print(" ( " + ++m_exportCount + " ) ", I_CmsReport.C_FORMAT_NOTE);
        getReport().print(getReport().key("report.exporting"), I_CmsReport.C_FORMAT_NOTE);
        getReport().print(getCms().getSitePath(file));
        getReport().print(getReport().key("report.dots"));

        // store content in zip-file
        // check if the content of this resource was not already exported
        if (!m_exportedResources.contains(file.getResourceId())) {
            ZipEntry entry = new ZipEntry(source);
            // save the time of the last modification in the zip
            entry.setTime(file.getDateLastModified());
            getExportZipStream().putNextEntry(entry);
            getExportZipStream().write(file.getContents());
            getExportZipStream().closeEntry();
            // add the resource id to the storage to mark that this resource was already exported
            m_exportedResources.add(file.getResourceId());
            // create the manifest-entrys
            appendResourceToManifest(file, true);
        } else {
            // only create the manifest-entrys
            appendResourceToManifest(file, false);
        }
        // check if the resource is a page of the old style. if so, export the body as well       
        if (OpenCms.getResourceManager().getResourceType(file.getTypeId()).getTypeName().equals("page")) {
            m_exportedPageFiles.add("/" + source);
        }

        if (OpenCms.getLog(this).isInfoEnabled()) {
            OpenCms.getLog(this).info(
                "( "
                    + m_exportCount
                    + " ) "
                    + m_report.key("report.exporting")
                    + source
                    + m_report.key("report.dots")
                    + m_report.key("report.ok"));
        }
        getReport().println(" " + getReport().key("report.ok"), I_CmsReport.C_FORMAT_OK);
    }

    /**
     * Exports one single group with all it's data.<p>
     *
     * @param parent the parent node to add the groups to
     * @param group the group to be exported
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void exportGroup(Element parent, CmsGroup group) throws CmsImportExportException, SAXException {

        try {
            String parentgroup;
            if (group.getParentId().isNullUUID()) {
                parentgroup = "";
            } else {
                parentgroup = getCms().getParent(group.getName()).getName();
            }
    
            Element e = parent.addElement(I_CmsConstants.C_EXPORT_TAG_GROUPDATA);
            e.addElement(I_CmsConstants.C_EXPORT_TAG_NAME).addText(group.getName());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_DESCRIPTION).addCDATA(group.getDescription());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_FLAGS).addText(Integer.toString(group.getFlags()));
            e.addElement(I_CmsConstants.C_EXPORT_TAG_PARENTGROUP).addText(parentgroup);
    
            // write the XML
            digestElement(parent, e);
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_READING_PARENT_GROUP_1, group.getName());
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Exports all groups with all data.<p>
     *
     * @param parent the parent node to add the groups to
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void exportGroups(Element parent) throws CmsImportExportException, SAXException {

        try {
            List allGroups = getCms().getGroups();
            for (int i = 0, l = allGroups.size(); i < l; i++) {
                CmsGroup group = (CmsGroup)allGroups.get(i);
                getReport().print(" ( " + (i + 1) + " / " + l + " ) ", I_CmsReport.C_FORMAT_NOTE);
                getReport().print(getReport().key("report.exporting_group"), I_CmsReport.C_FORMAT_NOTE);
                getReport().print(group.getName());
                getReport().print(getReport().key("report.dots"));
                exportGroup(parent, group);
                getReport().println(getReport().key("report.ok"), I_CmsReport.C_FORMAT_OK);
            }
        } catch (CmsImportExportException e) {
            
            throw e;
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_READING_ALL_GROUPS_0);
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Exports one single user with all its data.<p>
     * 
     * @param parent the parent node to add the users to
     * @param user the user to be exported
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void exportUser(Element parent, CmsUser user) throws CmsImportExportException, SAXException {

        try {
            // add user node to the manifest.xml
            Element e = parent.addElement(I_CmsConstants.C_EXPORT_TAG_USERDATA);
            e.addElement(I_CmsConstants.C_EXPORT_TAG_NAME).addText(user.getName());
            // encode the password, using a base 64 decoder
            String passwd = new String(Base64.encodeBase64(user.getPassword().getBytes()));
            e.addElement(I_CmsConstants.C_EXPORT_TAG_PASSWORD).addCDATA(passwd);
            e.addElement(I_CmsConstants.C_EXPORT_TAG_DESCRIPTION).addCDATA(user.getDescription());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_FIRSTNAME).addText(user.getFirstname());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_LASTNAME).addText(user.getLastname());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_EMAIL).addText(user.getEmail());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_FLAGS).addText(Integer.toString(user.getFlags()));
            e.addElement(I_CmsConstants.C_EXPORT_TAG_ADDRESS).addCDATA(user.getAddress());
            e.addElement(I_CmsConstants.C_EXPORT_TAG_TYPE).addText(Integer.toString(user.getType()));
            // serialize the hashtable and write the info into a file
            try {
                String datfileName = "/~" + I_CmsConstants.C_EXPORT_TAG_USERINFO + "/" + user.getName() + ".dat";
                // create tag for userinfo
                e.addElement(I_CmsConstants.C_EXPORT_TAG_USERINFO).addText(datfileName);
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ObjectOutputStream oout = new ObjectOutputStream(bout);
                oout.writeObject(user.getAdditionalInfo());
                oout.close();
                byte[] serializedInfo = bout.toByteArray();
                // store the serialized  user info hashtable in the zip-file
                ZipEntry entry = new ZipEntry(datfileName);
                getExportZipStream().putNextEntry(entry);
                getExportZipStream().write(serializedInfo);
                getExportZipStream().closeEntry();
            } catch (IOException ioe) {
                getReport().println(ioe);
                
                if (LOG.isErrorEnabled()) {
                    LOG.error(Messages.get().key(Messages.ERR_IMPORTEXPORT_ERROR_EXPORTING_USER_1, user.getName()), ioe);
                }   
            }
            // append the node for groups of user
            List userGroups = getCms().getDirectGroupsOfUser(user.getName());
            Element g = e.addElement(I_CmsConstants.C_EXPORT_TAG_USERGROUPS);
            for (int i = 0; i < userGroups.size(); i++) {
                String groupName = ((CmsGroup)userGroups.get(i)).getName();
                g.addElement(I_CmsConstants.C_EXPORT_TAG_GROUPNAME).addElement(I_CmsConstants.C_EXPORT_TAG_NAME).addText(
                    groupName);
            }
            // write the XML
            digestElement(parent, e);
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_READING_GROUPS_OF_USER_1, user.getName());
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Exports all users with all data.<p>
     *
     * @param parent the parent node to add the users to
     * @throws CmsImportExportException if something goes wrong
     * @throws SAXException if something goes wrong procesing the manifest.xml
     */
    private void exportUsers(Element parent) throws CmsImportExportException, SAXException {

        try {
            List allUsers = getCms().getUsers();
            for (int i = 0, l = allUsers.size(); i < l; i++) {
                CmsUser user = (CmsUser)allUsers.get(i);
                getReport().print(" ( " + (i + 1) + " / " + l + " ) ", I_CmsReport.C_FORMAT_NOTE);
                getReport().print(getReport().key("report.exporting_user"), I_CmsReport.C_FORMAT_NOTE);
                getReport().print(user.getName());
                getReport().print(getReport().key("report.dots"));
                exportUser(parent, user);
                getReport().println(getReport().key("report.ok"), I_CmsReport.C_FORMAT_OK);
            }
        } catch (CmsImportExportException e) {
            
            throw e;
        } catch (CmsException e) {
            
            CmsMessageContainer message = Messages.get().container(Messages.ERR_IMPORTEXPORT_ERROR_READING_ALL_USERS_0);
            if (LOG.isDebugEnabled()) {
                LOG.debug(message, e);
            }
            
            throw new CmsImportExportException(message, e);
        }
    }

    /**
     * Cuts leading and trailing '/' from the given resource name.<p>
     * 
     * @param resourceName the absolute path of a resource
     * @return the trimmed resource name
     */
    private String trimResourceName(String resourceName) {

        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }
        if (resourceName.endsWith("/")) {
            resourceName = resourceName.substring(0, resourceName.length() - 1);
        }
        return resourceName;
    }
}
