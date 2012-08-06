/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2009, 2010, 2011 Olivier Bilodeau <olivier@bottomlesspit.org>
 * Copyright 2009, 2010 Benoit Garret <benoit.garret_launchpad@gadz.org>
 * Copyright 2011 Stefan Hammer <j.4@gmx.at>
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
package org.tomdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Time;
import android.widget.ListAdapter;
import android.widget.Toast;

import org.tomdroid.sync.SyncManager;
import org.tomdroid.ui.SyncDialog;
import org.tomdroid.ui.Tomdroid;
import org.tomdroid.util.NoteListCursorAdapter;
import org.tomdroid.util.Preferences;
import org.tomdroid.util.TLog;
import org.tomdroid.util.XmlUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoteManager {
	
	public static final String[] FULL_PROJECTION = { Note.ID, Note.TITLE, Note.FILE, Note.NOTE_CONTENT, Note.MODIFIED_DATE, Note.GUID, Note.TAGS };
	public static final String[] LIST_PROJECTION = { Note.ID, Note.TITLE, Note.MODIFIED_DATE, Note.TAGS };
	public static final String[] DATE_PROJECTION = { Note.ID, Note.GUID, Note.MODIFIED_DATE };
	public static final String[] TITLE_PROJECTION = { Note.TITLE };
	public static final String[] GUID_PROJECTION = { Note.ID, Note.GUID };
	public static final String[] ID_PROJECTION = { Note.ID };
	public static final String[] EMPTY_PROJECTION = {};
	
	// static properties
	private static final String TAG = "NoteManager";
	private static String lastGuid;
	private static String lastSyncGUID;
	
	// gets a note from the content provider
	public static Note getNote(Activity activity, Uri uri) {
		
		Note note = null;
		
		// can we find a matching note?
		Cursor cursor = activity.managedQuery(uri, FULL_PROJECTION, null, null, null);
		// cursor must not be null and must return more than 0 entry 
		if (!(cursor == null || cursor.getCount() == 0)) {
			
			// create the note from the cursor
			cursor.moveToFirst();
			String noteContent = cursor.getString(cursor.getColumnIndexOrThrow(Note.NOTE_CONTENT));
			String noteTitle = cursor.getString(cursor.getColumnIndexOrThrow(Note.TITLE));
			String noteChangeDate = cursor.getString(cursor.getColumnIndexOrThrow(Note.MODIFIED_DATE));
			String noteTags = cursor.getString(cursor.getColumnIndexOrThrow(Note.TAGS));
			String noteGUID = cursor.getString(cursor.getColumnIndexOrThrow(Note.GUID));
			int noteDbid = cursor.getInt(cursor.getColumnIndexOrThrow(Note.ID));
			
			note = new Note();
			note.setTitle(noteTitle);
			note.setXmlContent(stripTitleFromContent(noteContent, noteTitle));
			note.setLastChangeDate(noteChangeDate);
			note.addTag(noteTags);
			note.setGuid(noteGUID);
			note.setDbId(noteDbid);
		}
		
		return note;
	}
	
	// puts all notes in the content provider
	// this allows us to figure out which is the last note
	// boolean push means we are syncing
	public static void putNotes(Activity activity, Note[] notes, boolean push) {
		if(push)
			setLastGuid(notes[notes.length-1].getGuid());
		for (Note note : notes) {
			putNote(activity,note,push);
		}
	}
	
	private static void setLastGuid(String guid) {
		lastGuid = guid;		
	}

	// set the latest GUID to send to service
	private static void setLastSyncGUID(String guid) {
		lastSyncGUID = guid;
	}
	
	// puts a note in the content provider
	// return uri
	// boolean push means we are syncing
	public static Uri putNote(Activity activity, Note note, boolean push) {
		
		// verify if the note is already in the content provider
		
		// TODO make the query prettier (use querybuilder)
		Uri notes = Tomdroid.CONTENT_URI;
		String[] whereArgs = new String[1];
		whereArgs[0] = note.getGuid().toString();
		
		// The note identifier is the guid
		ContentResolver cr = activity.getContentResolver();
		Cursor managedCursor = cr.query(notes,
                LIST_PROJECTION,  
                Note.GUID + "= ?",
                whereArgs,
                null);
		activity.startManagingCursor(managedCursor);
		
		// Preparing the values to be either inserted or updated
		// depending on the result of the previous query
		ContentValues values = new ContentValues();
		values.put(Note.TITLE, note.getTitle());
		values.put(Note.FILE, note.getFileName());
		values.put(Note.GUID, note.getGuid().toString());
		// Notice that we store the date in UTC because sqlite doesn't handle RFC3339 timezone information
		values.put(Note.MODIFIED_DATE, note.getLastChangeDate().format3339(false));
		values.put(Note.NOTE_CONTENT, note.getXmlContent());
		values.put(Note.TAGS, note.getTags());
		
		Uri uri;
		
		if (managedCursor == null || managedCursor.getCount() == 0) {
			
			// This note is not in the database yet we need to insert it
			TLog.v(TAG, "A new note has been detected (not yet in db)");
			
    		uri = cr.insert(Tomdroid.CONTENT_URI, values);

    		TLog.v(TAG, "Note inserted in content provider. ID: {0} TITLE:{1} GUID:{2}", uri, note.getTitle(),
                    note.getGuid());
		} else {
			
			TLog.v(TAG, "A local note has been detected (already in db)");

			managedCursor.moveToFirst();
			uri = Uri.parse(Tomdroid.CONTENT_URI + "/" + managedCursor.getString(managedCursor.getColumnIndexOrThrow(Note.ID)));
			Note localNote = getNote(activity,uri);
			
			// check date difference
			
			String oldDateString = managedCursor.getString(managedCursor.getColumnIndexOrThrow(Note.MODIFIED_DATE));
			Time oldDate = new Time();
			oldDate.parse3339(oldDateString);

			String syncDateString = Preferences.getString(Preferences.Key.LATEST_SYNC_DATE);
			Time syncDate = new Time();
			syncDate.parse3339(syncDateString);
			
			int compareSyncLocal = Time.compare(syncDate, oldDate);
			int compareSyncRemote = Time.compare(syncDate, note.getLastChangeDate());
			int compareBoth = Time.compare(oldDate, note.getLastChangeDate());
			
			TLog.v(TAG, "compare both: {0}, compare local: {1}, compare remote: {2}", compareBoth, compareSyncLocal, compareSyncRemote);
			if(compareBoth != 0)
				TLog.v(TAG, "Different note dates");
			if((compareSyncLocal < 0 && compareSyncRemote < 0) || (compareSyncLocal > 0 && compareSyncRemote > 0))
				TLog.v(TAG, "both either older or newer");
			if(push)
				TLog.v(TAG, "pushing");
				
			if(compareBoth != 0 && ((compareSyncLocal < 0 && compareSyncRemote < 0) || (compareSyncLocal > 0 && compareSyncRemote > 0)) && push) { // sync conflict!  both are older or newer than last sync
				
				TLog.v(TAG, "note conflict... showing resolution dialog");
				
				// send everything to Tomdroid so it can show Sync Dialog
			    Bundle bundle = new Bundle();	
				bundle.putString("uri",uri.toString());	
				bundle.putString("title",note.getTitle());
				bundle.putString("file",note.getFileName());
				bundle.putString("guid",note.getGuid());
				bundle.putString("date",note.getLastChangeDate().format3339(false));
				bundle.putString("content", note.getXmlContent());
				bundle.putString("tags", note.getTags());
				bundle.putInt("datediff", compareBoth);

				bundle.putBoolean("lastSync", lastSyncGUID == null);
				Intent intent = new Intent(activity.getApplicationContext(), SyncDialog.class);	
				intent.putExtras(bundle);

				if(lastSyncGUID == null) {// make the first the last, since it will be on the bottom of the stack
					TLog.d(TAG, "setting conflict {0} as last to sync", note.getGuid());
					lastSyncGUID = note.getGuid();
					activity.startActivityForResult(intent, 0);		
				}
				else
					activity.startActivity(intent);		
			}
			else if(compareBoth > 0) { // local newer 
				TLog.v(TAG, "local newer, pushing local to remote");

					/* pushing local changes, reject older incoming note.
					 * If the local counterpart has the tag "system:deleted", delete from both local and remote.
					 * Otherwise, push local to remote.
					 */
					
					if(localNote.getTags().contains("system:deleted")) {
						TLog.v(TAG, "local note is deleted, deleting from server TITLE:{0} GUID:{1}", localNote.getTitle(),localNote.getGuid());
						SyncManager.getInstance().deleteNote(localNote.getGuid()); // delete from remote
						deleteNote(activity,localNote.getDbId()); // really delete locally
					}
					else {
						TLog.v(TAG, "local note is newer, sending new version TITLE:{0} GUID:{1}", localNote.getTitle(),localNote.getGuid());
						SyncManager.getInstance().pushNote(localNote);
					}
					if(push)
						setLastSyncGUID(localNote.getGuid());
			}
			else if(compareBoth < 0) { // local older
				TLog.v(TAG, "Local note is older, updating in content provider TITLE:{0} GUID:{1}", note.getTitle(), note.getGuid());
				
				// pull remote changes
				
				cr.update(Tomdroid.CONTENT_URI, values, Note.GUID+" = ?", whereArgs);
				
			}
			else { // both same date
				cr.update(Tomdroid.CONTENT_URI, values, Note.GUID+" = ?", whereArgs); // update anyway, for debugging

				TLog.v(TAG, "Local note is same date, skipped: TITLE:{0} GUID:{1}", note.getTitle(), note.getGuid());
			}
		}
		if(lastGuid == note.getGuid()) {
			if(lastSyncGUID == null) {// last note, not sending any notes, finish sync 
				SyncManager.getInstance().getCurrentService().finishSync(false);
				Intent intent = new Intent(activity.getApplicationContext(), SyncDialog.class); // start empty to trigger refresh FIXME
				activity.startActivity(intent);		
			}
			else { // last note, sending notes, tell sync which is the last to sync
				TLog.d(TAG, "setting {0} as last sync GUID", note.getTitle(), note.getGuid());
				SyncManager.getInstance().getCurrentService().setLastGUID(lastSyncGUID);
				lastSyncGUID = null;
			}
		}
		return uri;
	}
	
	// this function just adds a "deleted" tag, to allow remote delete when syncing
	public static void deleteNote(Activity activity, Note note)
	{
		note.addTag("system:deleted");
		Time now = new Time();
		now.setToNow();
		note.setLastChangeDate(now);
		putNote(activity,note, false);		
		Toast.makeText(activity, activity.getString(R.string.messageNoteDeleted), Toast.LENGTH_SHORT).show();
	}

	// this function actually deletes the note locally, called when syncing
	public static boolean deleteNote(Activity activity, int id)
	{
		Uri uri = Uri.parse(Tomdroid.CONTENT_URI+"/"+id);

		ContentResolver cr = activity.getContentResolver();
		int result = cr.delete(uri, null, null);
		
		if(result > 0) {
			return true;
		}
		else 
			return false;
	}

	// this function deletes deleted notes - if they never existed on the server, we still delete them at sync

	public static void deleteDeletedNotes(Activity activity)
	{
		// get a cursor representing all deleted notes from the NoteProvider
		Uri notes = Tomdroid.CONTENT_URI;
		String where = Note.TAGS + " LIKE '%system:deleted%'";
		ContentResolver cr = activity.getContentResolver();
		int rows = cr.delete(notes, where, null);
		TLog.v(TAG, "Deleted {0} local notes based on system:deleted tag",rows);
	}

	// this function deletes all notes - called from preferences

	public static void deleteAllNotes(Activity activity)
	{
		// get a cursor representing all deleted notes from the NoteProvider
		Uri notes = Tomdroid.CONTENT_URI;
		ContentResolver cr = activity.getContentResolver();
		int rows = cr.delete(notes, null, null);
		TLog.v(TAG, "Deleted {0} local notes",rows);
	}

	public static Cursor getAllNotes(Activity activity, Boolean includeNotebookTemplates) {
		// get a cursor representing all notes from the NoteProvider
		Uri notes = Tomdroid.CONTENT_URI;
		String where = "("+Note.TAGS + " NOT LIKE '%" + "system:deleted" + "%')";
		String orderBy;
		if (!includeNotebookTemplates) {
			where += " AND (" + Note.TAGS + " NOT LIKE '%" + "system:template" + "%')";
		}
		orderBy = Note.MODIFIED_DATE + " DESC";
		return activity.managedQuery(notes, LIST_PROJECTION, where, null, orderBy);		
	}
	

	public static ListAdapter getListAdapter(Activity activity, String querys) {
		
		boolean includeNotebookTemplates = Preferences.getBoolean(Preferences.Key.INCLUDE_NOTE_TEMPLATES);
		
		String where = "(" + Note.TAGS + " NOT LIKE '%" + "system:deleted" + "%')";
		if (!includeNotebookTemplates) {
			where += " AND (" + Note.TAGS + " NOT LIKE '%" + "system:template" + "%')";
		}
		if (querys != null ) {
			// sql statements to search notes
			String[] query = querys.split(" ");
			for (String string : query) {
				where = where + " AND ("+Note.TITLE+" LIKE '%"+string+"%' OR "+Note.NOTE_CONTENT+" LIKE '%"+string+"%')";
			}	
		}

		// get a cursor representing all notes from the NoteProvider
		Uri notes = Tomdroid.CONTENT_URI;
		Cursor notesCursor = activity.managedQuery(notes, LIST_PROJECTION, where, null, null);
		
		// set up an adapter binding the TITLE field of the cursor to the list item
		String[] from = new String[] { Note.TITLE };
		int[] to = new int[] { R.id.note_title };
		return new NoteListCursorAdapter(activity, R.layout.main_list_item, notesCursor, from, to);
	}
	
	public static ListAdapter getListAdapter(Activity activity) {
		
		return getListAdapter(activity, null);
	}

	// gets the titles of the notes present in the db, used in ViewNote.buildLinkifyPattern()
	public static Cursor getTitles(Activity activity) {
		
		// get a cursor containing the notes titles
		return activity.managedQuery(Tomdroid.CONTENT_URI, TITLE_PROJECTION, null, null, null);
	}
	
	// gets the ids of the notes present in the db, used in SyncService.deleteNotes()
	public static Cursor getGuids(Activity activity) {
		
		// get a cursor containing the notes guids
		return activity.managedQuery(Tomdroid.CONTENT_URI, GUID_PROJECTION, null, null, null);
	}
	
	public static int getNoteId(Activity activity, String title) {
		
		int id = 0;
		
		// get the notes ids
		String[] whereArgs = { title };
		Cursor cursor = activity.managedQuery(Tomdroid.CONTENT_URI, ID_PROJECTION, Note.TITLE+"=?", whereArgs, null);
		
		// cursor must not be null and must return more than 0 entry 
		if (!(cursor == null || cursor.getCount() == 0)) {
			
			cursor.moveToFirst();
			id = cursor.getInt(cursor.getColumnIndexOrThrow(Note.ID));
		}
		else {
			// TODO send an error to the user
			TLog.d(TAG, "Cursor returned null or 0 notes");
		}
		
		return id;
	}
	
	/**
	 * stripTitleFromContent
	 * Because of an historic oddity in Tomboy's note format, a note's title is in a <title> tag but is also repeated
	 * in the <note-content> tag. This method strips it from <note-content>.
	 * @param noteContent
	 */
	private static String stripTitleFromContent(String xmlContent, String title) {
		// get rid of the title that is doubled in the note's content
		// using quote to escape potential regexp chars in pattern
		
		Pattern stripTitle = Pattern.compile("^\\s*"+Pattern.quote(XmlUtils.escape(title))+"\\n\\n"); 
		Matcher m = stripTitle.matcher(xmlContent);
		if (m.find()) {
			xmlContent = xmlContent.substring(m.end(), xmlContent.length());
			TLog.d(TAG, "stripped the title from note-content");
		}
		
		return xmlContent;
	}
	
	/**
	 * getNewNotes
	 * get a guid list of notes that are newer than latest sync date 
	 * @param activity
	 */
	public static Cursor getNewNotes(Activity activity) {
		Cursor cursor = activity.managedQuery(Tomdroid.CONTENT_URI, DATE_PROJECTION, "strftime('%s', "+Note.MODIFIED_DATE+") > strftime('%s', '"+Preferences.getString(Preferences.Key.LATEST_SYNC_DATE)+"')", null, null);	
				
		return cursor;
	}
}