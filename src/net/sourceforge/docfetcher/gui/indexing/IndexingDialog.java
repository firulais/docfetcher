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

package net.sourceforge.docfetcher.gui.indexing;

import java.util.Collections;
import java.util.List;

import net.sourceforge.docfetcher.base.Event;
import net.sourceforge.docfetcher.base.Util;
import net.sourceforge.docfetcher.base.annotations.NotNull;
import net.sourceforge.docfetcher.base.annotations.Nullable;
import net.sourceforge.docfetcher.base.annotations.VisibleForPackageGroup;
import net.sourceforge.docfetcher.base.gui.TabFolderFactory;
import net.sourceforge.docfetcher.enums.Img;
import net.sourceforge.docfetcher.enums.ProgramConf;
import net.sourceforge.docfetcher.enums.SettingsConf;
import net.sourceforge.docfetcher.gui.IndexPanel;
import net.sourceforge.docfetcher.gui.indexing.KeepDiscardDialog.Answer;
import net.sourceforge.docfetcher.gui.indexing.SingletonDialogFactory.Dialog;
import net.sourceforge.docfetcher.model.IndexRegistry;
import net.sourceforge.docfetcher.model.index.DelegatingReporter.ErrorMessage;
import net.sourceforge.docfetcher.model.index.DelegatingReporter.ExistingMessagesHandler;
import net.sourceforge.docfetcher.model.index.DelegatingReporter.ExistingMessagesProvider;
import net.sourceforge.docfetcher.model.index.DelegatingReporter.InfoMessage;
import net.sourceforge.docfetcher.model.index.IndexingConfig;
import net.sourceforge.docfetcher.model.index.IndexingQueue.ExistingTasksHandler;
import net.sourceforge.docfetcher.model.index.Task;
import net.sourceforge.docfetcher.model.index.Task.CancelAction;
import net.sourceforge.docfetcher.model.index.Task.CancelHandler;
import net.sourceforge.docfetcher.model.index.Task.IndexAction;
import net.sourceforge.docfetcher.model.index.Task.TaskState;
import net.sourceforge.docfetcher.model.index.file.FileIndex;
import net.sourceforge.docfetcher.model.index.outlook.OutlookIndex;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;

/**
 * @author Tran Nam Quang
 */
@VisibleForPackageGroup
public final class IndexingDialog implements Dialog {

	private final Shell shell;
	private final CTabFolder tabFolder;
	private final IndexRegistry indexRegistry;

	public IndexingDialog(	@NotNull Shell parentShell,
							@NotNull final IndexRegistry indexRegistry) {
		this.indexRegistry = Util.checkNotNull(indexRegistry);

		// TODO
//		// Open the indexing dialog only if DocFetcher doesn't hide in the system tray
//		if (DocFetcher.getInstance().getShell().isVisible()) {
//			shell.open();
//			tabFolder.setSelectionBackground(UtilGUI.getColor(SWT.COLOR_TITLE_BACKGROUND));
//			tabFolder.setSelectionForeground(UtilGUI.getColor(SWT.COLOR_TITLE_FOREGROUND));
//		}

		// Create shell
		shell = new Shell(parentShell, SWT.SHELL_TRIM | SWT.PRIMARY_MODAL);
		shell.setText("index_management"); // TODO i18n
		shell.setImage(Img.INDEXING_DIALOG.get());
		SettingsConf.ShellBounds.IndexingDialog.bind(shell);
		shell.setLayout(Util.createFillLayout(5));

		// Create tabfolder
		boolean curvyTabs = ProgramConf.Bool.CurvyTabs.get();
		boolean coloredTabs = ProgramConf.Bool.ColoredTabs.get();
		tabFolder = TabFolderFactory.create(shell, true, curvyTabs, coloredTabs);

		// Create tabfolder toolbar
		ToolBar toolBar = new ToolBar(tabFolder, SWT.FLAT);
		tabFolder.setTopRight(toolBar);

		// TODO i18n
		// Create Add-Directory button
		Util.createToolItem(
			toolBar, Img.FOLDER.get(), null, "add_to_queue",
			new SelectionAdapter() {
				public void widgetSelected(SelectionEvent e) {
					IndexPanel.createFileTaskFromDialog(
						shell, indexRegistry, null);
				}
			});

		// TODO i18n, find more suitable image (mail-like)
		// Create Add-Outlook button
		Util.createToolItem(
			toolBar, Img.EMAIL.get(), null, "add_to_queue",
			new SelectionAdapter() {
				public void widgetSelected(SelectionEvent e) {
					IndexPanel.createOutlookTaskFromDialog(
						shell, indexRegistry, null);
				}
			});

		// TODO maybe try computeSize() instead of getSize()?
//		tabFolder.setTabHeight((int) (toolBar.getSize().y * 1.5)); // A factor of 1.0 would crop the add button image.

		// For some unknown reason, the focus always goes to the ToolBar items
		toolBar.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent e) {
				tabFolder.forceFocus();
			}
		});
		
		initEventHandlers();
	}
	
	private void initEventHandlers() {
		// Handler for task addition events
		final Event.Listener<Task> addedListener = new Event.Listener<Task>() {
			public void update(final Task task) {
				assert !shell.isDisposed();
				Util.runSWTSafe(tabFolder, new Runnable() {
					public void run() {
						addTab(task, !task.is(IndexAction.UPDATE));
					}
				});
			}
		};
		
		// Handler for task removal events
		final Event.Listener<Task> removedListener = new Event.Listener<Task>() {
			public void update(final Task task) {
				assert !shell.isDisposed();
				Util.runSWTSafe(tabFolder, new Runnable() {
					public void run() {
						for (CTabItem item : tabFolder.getItems()) {
							if (item.getData() == task) {
								item.dispose();
								break;
							}
						}
					}
				});
				/*
				 * If there are no more tabs, close the indexing dialog. Note
				 * that detaching the listeners cannot be done in another
				 * thread; trying to do so would cause a deadlock.
				 */
				if (tabFolder.getItemCount() == 0) {
					indexRegistry.getQueue().removeListeners(
						addedListener, this);
					Util.runSWTSafe(shell, new Runnable() {
						public void run() {
							shell.dispose();
						}
					});
				}
			}
		};
		
		/*
		 * Hook onto the indexing queue, i.e. register the listeners and create
		 * tabs for existing tasks as necessary.
		 */
		indexRegistry.getQueue().addListeners(new ExistingTasksHandler() {
			public void handleExistingTasks(List<Task> tasks) {
				for (Task task : tasks)
					addTab(task, false);
			}
		}, addedListener, removedListener);
		
		/*
		 * When the indexing dialog is closed, cancel all tasks and unregister
		 * the listeners.
		 */
		shell.addShellListener(new ShellAdapter() {
			public void shellClosed(final ShellEvent e) {
				indexRegistry.getQueue().removeAll(new CancelHandler() {
					public CancelAction cancel() {
						CancelAction action = confirmCancel();
						e.doit = action != null;
						return action;
					}
				}, addedListener, removedListener);
			}
		});
		
		// Handle closing of tabs by the user
		tabFolder.addCTabFolder2Listener(new CTabFolder2Adapter() {
			public void close(final CTabFolderEvent event) {
				Task task = (Task) event.item.getData();
				task.remove(new CancelHandler() {
					public CancelAction cancel() {
						CancelAction action = confirmCancel();
						event.doit = action != null;
						return action;
					}
				});
			}
		});
	}
	
	@Nullable
	private CancelAction confirmCancel() {
		KeepDiscardDialog dialog = new KeepDiscardDialog(shell);
		Answer answer = dialog.open();
		switch (answer) {
		case KEEP:
			return CancelAction.KEEP;
		case DISCARD:
			return CancelAction.DISCARD;
		case CONTINUE:
			return null;
		}
		throw new IllegalStateException();
	}

	@NotNull
	public Shell getShell() {
		return shell;
	}

	private void addTab(@NotNull final Task task, boolean selectTab) {
		// TODO set tab title, icon and tooltip
		final CTabItem tabItem = new CTabItem(tabFolder, SWT.CLOSE);
		tabItem.setText("Test");
		tabItem.setImage(Img.DOCFETCHER_16.get());
		tabItem.setData(task);
		
		/*
		 * The tab item's control will not be disposed when the tab item is
		 * disposed, so this dispose listener is necessary. Note that the
		 * control to be disposed might be either the configuration panel or the
		 * progress panel, so calling configPanel.dispose() is not correct.
		 */
		tabItem.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				tabItem.getControl().dispose();
			}
		});

		IndexingConfig config = task.getLuceneIndex().getConfig();
		
		if (task.is(IndexAction.UPDATE) || !task.is(TaskState.NOT_READY)) {
			switchToProgressPanel(task, tabItem, config);
		}
		else {
			final ConfigPanel configPanel;
			if (task.getLuceneIndex() instanceof FileIndex)
				configPanel = new FileConfigPanel(tabFolder, config);
			else if (task.getLuceneIndex() instanceof OutlookIndex)
				configPanel = new OutlookConfigPanel(tabFolder, config);
			else
				throw new IllegalStateException();
			tabItem.setControl(configPanel);

			/*
			 * Move focus away from tab item, or else the tab title will be
			 * underlined.
			 */
			configPanel.setFocus();

			configPanel.evtRunButtonClicked.add(new Event.Listener<IndexingConfig>() {
				public void update(final IndexingConfig config) {
					// TODO update tab image
					// switch to next waiting tab

					configPanel.dispose();
					switchToProgressPanel(task, tabItem, config);
					task.setReady();
				}
			});
		}

		if (selectTab)
			tabFolder.setSelection(tabItem);
	}

	private void switchToProgressPanel(	@NotNull final Task task,
										@NotNull final CTabItem tabItem,
										@NotNull final IndexingConfig config) {
		int lineCountLimit = ProgramConf.Int.MaxLinesInProgressPanel.get();
		final ProgressPanel progressPanel = new ProgressPanel(
			tabFolder, lineCountLimit);
		tabItem.setControl(progressPanel.getControl());
		final ProgressReporter reporter = new ProgressReporter(progressPanel);
		
		task.attachReporter(reporter, new ExistingMessagesHandler() {
			public void handleMessages(	List<InfoMessage> infoMessages,
										List<ErrorMessage> errorMessages) {
				// TODO fill outer reporter with info and error messages
			}
		});
		
		progressPanel.getControl().addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				task.detachReporter(reporter, new ExistingMessagesProvider() {
					public List<InfoMessage> getInfoMessages() {
						// TODO
						return Collections.emptyList();
					}
					
					public List<ErrorMessage> getErrorMessages() {
						// TODO
						return Collections.emptyList();
					}
				});
			}
		});
	}

}