/*******************************************************************************
 * Copyright (c) 2011 Tran Nam Quang.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Tran Nam Quang - initial API and implementation
 *******************************************************************************/

package net.sourceforge.docfetcher.model.search;

import java.io.File;
import java.util.Date;
import net.sourceforge.docfetcher.base.Util;
import net.sourceforge.docfetcher.base.annotations.NotNull;
import net.sourceforge.docfetcher.base.annotations.ThreadSafe;
import net.sourceforge.docfetcher.model.DocumentType;
import net.sourceforge.docfetcher.model.Field;
import net.sourceforge.docfetcher.model.FileResource;
import net.sourceforge.docfetcher.model.MailResource;
import net.sourceforge.docfetcher.model.index.IndexingConfig;
import net.sourceforge.docfetcher.model.index.file.FileFactory;
import net.sourceforge.docfetcher.model.index.outlook.OutlookMailFactory;
import net.sourceforge.docfetcher.model.parse.HtmlParser;
import net.sourceforge.docfetcher.model.parse.PagingPdfParser;
import net.sourceforge.docfetcher.model.parse.ParseException;
import net.sourceforge.docfetcher.model.parse.ParseService;
import net.sourceforge.docfetcher.model.parse.Parser;
import net.sourceforge.docfetcher.model.parse.PdfParser;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;

/**
 * @author Tran Nam Quang
 * 
 * thread-safe class
 * throws UnsupportedOperationException if file methods are called on an email
 * object and vice versa.
 */
@ThreadSafe
public final class ResultDocument {
	
	public interface PdfPageHandler {
		public void handlePage(HighlightedString pageText);
		public boolean isStopped();
	}
	
	private final Document luceneDoc;
	private final float score;
	private final Query query;
	private final boolean isPhraseQuery;
	private final IndexingConfig config;
	private final FileFactory fileFactory;
	private final OutlookMailFactory mailFactory;
	
	// Cached values
	private final String uid;
	private final boolean isEmail;
	private String path;
	private String parentPath;
	private long sizeInKB = -1;
	private String parserName;
	
	public ResultDocument(	@NotNull Document luceneDoc,
							float score,
							@NotNull Query query,
							boolean isPhraseQuery,
							@NotNull IndexingConfig config,
							@NotNull FileFactory fileFactory,
							@NotNull OutlookMailFactory mailFactory) {
		Util.checkNotNull(luceneDoc, query, config, fileFactory, mailFactory);
		this.luceneDoc = luceneDoc;
		this.score = score;
		this.query = query;
		this.isPhraseQuery = isPhraseQuery;
		this.config = config;
		this.fileFactory = fileFactory;
		this.mailFactory = mailFactory;
		
		uid = luceneDoc.get(Field.UID.key());
		isEmail = DocumentType.isEmailType(uid);
	}
	
	private void onlyFiles() {
		if (isEmail)
			throw new UnsupportedOperationException();
	}
	
	private void onlyEmails() {
		if (! isEmail)
			throw new UnsupportedOperationException();
	}
	
	// returns filename title or email subject
	@NotNull
	public String getTitle() {
		String title = luceneDoc.get(Field.TITLE.key());
		if (title == null)
			title = luceneDoc.get(Field.SUBJECT.key());
		if (title != null)
			return title;
		return Util.splitFilename(getFilename())[0];
	}
	
	// score from 0 to 100
	public int getScore() {
		return Math.round(score * 100);
	}
	
	public long getSizeInKB() {
		if (sizeInKB < 0) {
			String sizeString = luceneDoc.get(Field.SIZE.key());
			assert sizeString != null;
			long sizeInBytes = Long.valueOf(sizeString);
			long extra = sizeInBytes % 1024 == 0 ? 0 : 1;
			sizeInKB = sizeInBytes / 1024 + extra;
		}
		return sizeInKB;
	}
	
	// Returns Field.EMAIL_PARSER for emails
	@NotNull
	public String getParserName() {
		if (parserName == null)
			parserName = luceneDoc.get(Field.PARSER.key());
		assert parserName != null;
		return parserName;
	}
	
	@NotNull
	public String getFilename() {
		onlyFiles();
		return luceneDoc.get(Field.FILENAME.key());
	}
	
	@NotNull
	public String getSender() {
		onlyEmails();
		return luceneDoc.get(Field.SENDER.key());
	}
	
	// returns file extension or mail type (Outlook, IMAP, etc.)
	@NotNull
	public String getType() {
		String type = luceneDoc.get(Field.TYPE.key());
		assert type != null;
		return type;
	}
	
	@NotNull
	public String getPath() {
		if (path == null)
			path =  DocumentType.extractPath(uid);
		return path;
	}
	
	@NotNull
	public String getParentPath() {
		if (parentPath == null)
			parentPath = Util.splitPathLast(getPath())[0];
		return parentPath;
	}
	
	// Returns authors for files, sender for emails
	@NotNull
	public String getAuthors() {
		String[] authors = luceneDoc.getValues(Field.AUTHOR.key());
		if (authors.length > 0)
			return Util.join(", ", authors);
		String sender = luceneDoc.get(Field.SENDER.key());
		return sender == null ? "" : sender;
	}
	
	@NotNull
	public Date getLastModified() {
		onlyFiles();
		String lastModified = luceneDoc.get(Field.LAST_MODIFIED.key());
		return new Date(Long.valueOf(lastModified));
	}
	
	@NotNull
	public Date getDate() {
		onlyEmails();
		String sendDate = luceneDoc.get(Field.DATE.key());
		return new Date(Long.valueOf(sendDate));
	}
	
	public boolean isEmail() {
		return isEmail;
	}

	public boolean isHtmlFile() {
		return wasParsedBy(HtmlParser.class);
	}
	
	public boolean isPdfFile() {
		return wasParsedBy(PdfParser.class);
	}
	
	private boolean wasParsedBy(Class<? extends Parser> parserClass) {
		String parserName = luceneDoc.get(Field.PARSER.key());
		return parserName.equals(parserClass.getSimpleName());
	}
	
	// Should be run in a thread
	// thrown parse exception has localized error message
	@NotNull
	private String getText() throws ParseException {
		onlyFiles();
		String parserName = luceneDoc.get(Field.PARSER.key());
		FileResource fileResource = null;
		try {
			fileResource = getFileResource();
			File file = fileResource.getFile();
			return ParseService.renderText(config, file, parserName);
		}
		finally {
			if (fileResource != null)
				fileResource.dispose();
		}
	}
	
	// Should be run in a thread
	// thrown parse exception has localized error message
	@NotNull
	public HighlightedString getHighlightedText() throws ParseException {
		return HighlightService.highlight(query, isPhraseQuery, getText());
	}
	
	// should be run in a thread
	public void readPdfPages(@NotNull final PdfPageHandler pageHandler)
			throws ParseException {
		// TODO i18n of error messages
		onlyFiles();
		Util.checkNotNull(pageHandler);
		FileResource fileResource = null;
		try {
			fileResource = getFileResource();
			new PagingPdfParser(fileResource.getFile()) {
				protected void handlePage(String pageText) {
					HighlightedString string = HighlightService.highlight(
							query, isPhraseQuery, pageText
					);
					pageHandler.handlePage(string);
					if (pageHandler.isStopped()) stop();
				}
			}.run();
		}
		finally {
			if (fileResource != null)
				fileResource.dispose();
		}
	}

	/**
	 * If the receiver represents a file, this method returns a {@code File} for
	 * it, wrapped in a {@code FileResource}. The caller <b>must</b> dispose of
	 * the FileResource after usage. The disposal is necessary since the
	 * returned {@code File} may represent a temporary file that was extracted
	 * from an archive and therefore needs to be disposed of after usage.
	 * <p>
	 * This operation may take a long time, so it should be run in a non-GUI
	 * thread.
	 */
	@NotNull
	public FileResource getFileResource() throws ParseException {
		onlyFiles();
		String path = DocumentType.extractPath(uid);
		IndexingConfig fileConfig = config;
		return fileFactory.createFile(fileConfig, path);
	}

	/**
	 * If the receiver represents an email, this method loads the email contents
	 * from disk and returns it as a {@code MailResource}. The caller
	 * <b>must</b> dispose of the MailResource after usage.
	 * <p>
	 * This operation may take a long time, so it should be run in a non-GUI
	 * thread.
	 */
	@NotNull
	public MailResource getMailResource() throws ParseException {
		onlyEmails();
		String path = DocumentType.extractPath(uid);
		return mailFactory.createMail(config, query, isPhraseQuery, path);
	}

}