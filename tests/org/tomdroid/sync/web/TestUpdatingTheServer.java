package org.tomdroid.sync.web;

import java.util.UUID;

import org.tomdroid.Note;

public class TestUpdatingTheServer extends MockedSyncServerTestCase {

	public void testChangingNoteOnClient() throws Exception {
		UUID guid = getServer().testDataManipulator.createNewNote().getGuid();
		getSyncMethod().syncWith(getServer());

		assertEquals("there should be no new/updated notes", 0, getLocalStorage()
				.getNewAndUpdatedNotes().size());
		Note localNote = modifyLocalNote(guid);
		assertEquals("should have a changed note", 1, getLocalStorage().getNewAndUpdatedNotes()
				.size());
		assertEquals("should have the changed note", guid, getLocalStorage()
				.getNewAndUpdatedNotes().get(0).getGuid());
		assertEquals("note should contain the update", localNote.getXmlContent(), getLocalStorage()
				.getNewAndUpdatedNotes().get(0).getXmlContent());
		assertFalse("should be out of sync", getServer().isInSync(getLocalStorage()));

		getSyncMethod().syncWith(getServer());
		assertEquals("should have no changed notes anymore", 0, getLocalStorage()
				.getNewAndUpdatedNotes().size());
		assertTrue("should be in sync", getServer().isInSync(getLocalStorage()));

		Note remoteNote = getServer().testDataManipulator.getNote(guid);
		assertEquals("remote note should have correct timestamp", localNote.getLastChangeDate()
				.format3339(false), remoteNote.getLastChangeDate().format3339(false));
		assertEquals("remote note should have been updated", localNote.getXmlContent(), remoteNote
				.getXmlContent());
	}

	public void testChangingDifferentNotesOnClientAndServer() throws Exception {
		UUID guid = getServer().testDataManipulator.createNewNote().getGuid();
		getSyncMethod().syncWith(getServer());

		modifyLocalNote(guid);
		getServer().testDataManipulator.createNewNote();
		getSyncMethod().syncWith(getServer());

		assertEquals(2, getLocalStorage().getNoteGuids().size());
		assertEquals(2, getServer().getNoteIds().size());
	}

	public void testServerNotStoringLocalModificationWhileSyncing() throws Exception {
		UUID guid = getServer().testDataManipulator.createNewNote().getGuid();
		getSyncMethod().syncWith(getServer());

		modifyLocalNote(guid);
		getServer().lockStoring();
		getSyncMethod().syncWith(getServer());
		getServer().unlockStoring();
		
		assertEquals("should still have the changed note", 1, getLocalStorage()
				.getNewAndUpdatedNotes().size());
		assertFalse("should be out of sync", getServer().isInSync(getLocalStorage()));
	}

	private Note modifyLocalNote(UUID guid) throws Exception {
		Note note = getLocalStorage().getNote(guid);
		long creationTime = note.getLastChangeDate().toMillis(false);
		Thread.sleep(1100);
		String newContent = note.getXmlContent() + "Appended text for our test note!";
		note.changeXmlContent(newContent);

		long modificationTime = note.getLastChangeDate().toMillis(false);
		assertTrue("timestamp should have changed", creationTime < modificationTime);

		getLocalStorage().insertNote(note);
		note = getLocalStorage().getNote(guid);
		assertEquals("timestamp should have been updated", modificationTime, note
				.getLastChangeDate().toMillis(false));
		assertEquals("local note should have been updated", newContent, note.getXmlContent());
		return note;
	}
}