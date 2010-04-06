/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2009, 2010 Olivier Bilodeau <olivier@bottomlesspit.org>
 * 
 * This file is part of Tomdroid.
 * 
 * Tomdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Tomdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Tomdroid.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tomdroid.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.tomdroid.Note;
import org.tomdroid.NoteManager;
import org.tomdroid.ui.Tomdroid;
import org.tomdroid.xml.NoteHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import android.util.TimeFormatException;

public class AsyncNoteLoaderAndParser {
	
	// thread pool info
	private final ExecutorService pool;
	private final static int poolSize = 1;
	
	// members
	private Activity activity;
	private File path;
	private Handler handler;
	
	// handler messages
	public final static int PARSING_COMPLETE = 1;
	public final static int PARSING_FAILED = 2;
	public final static int PARSING_NO_NOTES = 3;
	
	// regexp for <note-content..>...</note-content>
	private static Pattern note_content = Pattern.compile(".*(<note-content.*>.*<\\/note-content>).*", Pattern.CASE_INSENSITIVE+Pattern.DOTALL);
	
	// logging related
	private final static String TAG = "AsyncNoteLoaderAndParser";
	
	public AsyncNoteLoaderAndParser(Activity a, File path) {
		this.activity = a;
		this.path = path;
		
		pool = Executors.newFixedThreadPool(poolSize);
	}

	public void readAndParseNotes(Handler hndl) {
		handler = hndl;
		File[] fileList = path.listFiles(new NotesFilter());
		
		// If there are no notes, warn the UI through an empty message
		if (fileList.length == 0) {
			if (Tomdroid.LOGGING_ENABLED) Log.i(TAG, "There are no notes in "+path);
			handler.sendEmptyMessage(PARSING_NO_NOTES);
			return;
		}
		
		// every but the last note
		for(int i = 0; i < fileList.length-1; i++) {
			
			// give a filename to a thread and ask to parse it
			pool.execute(new Worker(fileList[i], false));
        }
		
		// last task, warn it so it'll warn UI when done
		pool.execute(new Worker(fileList[fileList.length-1], true));
	}
	
	/**
	 * Simple filename filter that grabs files ending with .note
	 * TODO move into its own static class in a util package
	 */
	private class NotesFilter implements FilenameFilter {
		public boolean accept(File dir, String name) {
			return (name.endsWith(".note"));
		}
	}
	
	/**
	 * The worker spawns a new note, parse the file its being given by the executor.
	 */
	// TODO change type to callable to be able to throw exceptions? (if you throw make sure to display an alert only once)
	// http://java.sun.com/j2se/1.5.0/docs/api/java/util/concurrent/Callable.html
	private class Worker implements Runnable {
		
		// the note to be loaded and parsed
		private Note note = new Note();
		private File file;
		private boolean isLast;
		final char[] buffer = new char[0x10000];
		
		public Worker(File f, boolean isLast) {
			file = f;
			this.isLast = isLast;
		}

		public void run() {
			
			note.setFileName(file.getAbsolutePath());
			// the note guid is not stored in the xml but in the filename
			note.setGuid(file.getName().replace(".note", ""));

			try {
				// Parsing
		    	// XML 
		    	// Get a SAXParser from the SAXPArserFactory
		        SAXParserFactory spf = SAXParserFactory.newInstance();
		        SAXParser sp = spf.newSAXParser();
		
		        // Get the XMLReader of the SAXParser we created
		        XMLReader xr = sp.getXMLReader();
		        
		        // Create a new ContentHandler, send it this note to fill and apply it to the XML-Reader
		        NoteHandler xmlHandler = new NoteHandler(note);
		        xr.setContentHandler(xmlHandler);

		        // Create the proper input source
		        FileInputStream fin = new FileInputStream(file);
		        BufferedReader in = new BufferedReader(new InputStreamReader(fin), 8192);
		        InputSource is = new InputSource(in);
		        
				if (Tomdroid.LOGGING_ENABLED) Log.d(TAG, "parsing note");
				xr.parse(is);
			
			// TODO wrap and throw a new exception here
			} catch (ParserConfigurationException e) {
				e.printStackTrace();
			} catch (SAXException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (TimeFormatException e) {
				e.printStackTrace();
				if (Tomdroid.LOGGING_ENABLED) Log.e(TAG, "Problem parsing the note's date and time");
				handler.sendEmptyMessage(PARSING_FAILED);
				return;
			}

			// extract the <note-content..>...</note-content>
			if (Tomdroid.LOGGING_ENABLED) Log.d(TAG, "retrieving what is inside of note-content");
			
			// FIXME here we are re-reading the whole note just to grab note-content out, there is probably a best way to do this (I'm talking to you xmlpull.org!)
			StringBuilder out = new StringBuilder();
			try {
				int read;
				Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
				
				do {
				  read = reader.read(buffer, 0, buffer.length);
				  if (read>0) {
				    out.append(buffer, 0, read);
				  }
				}
				while (read>=0);
				
				Matcher m = note_content.matcher(out.toString());
				if (m.find()) {
					note.setXmlContent(m.group());
				} else {
					if (Tomdroid.LOGGING_ENABLED) Log.w(TAG, "Something went wrong trying to grab the note-content out of a note");
				}

			} catch (IOException e) {
				// TODO handle properly
				e.printStackTrace();
				if (Tomdroid.LOGGING_ENABLED) Log.w(TAG, "Something went wrong trying to read the note");
			}
			
			NoteManager.putNote(AsyncNoteLoaderAndParser.this.activity, note);
			
			// if last note warn in UI that we are done
			if (isLast) {
				handler.sendEmptyMessage(PARSING_COMPLETE);
			}
		}
	}
}