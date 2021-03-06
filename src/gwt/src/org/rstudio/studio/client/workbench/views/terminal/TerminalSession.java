/*
 * TerminalSession.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.terminal;

import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.HandlerRegistrations;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.common.console.ConsoleOutputEvent;
import org.rstudio.studio.client.common.console.ConsoleProcess;
import org.rstudio.studio.client.common.console.ConsoleProcessInfo;
import org.rstudio.studio.client.common.console.ProcessExitEvent;
import org.rstudio.studio.client.common.shell.ShellInput;
import org.rstudio.studio.client.common.shell.ShellSecureInput;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.model.WorkbenchServerOperations;
import org.rstudio.studio.client.workbench.views.terminal.events.ResizeTerminalEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalCaptionEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalDataInputEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalTitleEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSessionStartedEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSessionStoppedEvent;
import org.rstudio.studio.client.workbench.views.terminal.xterm.XTermWidget;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.inject.Inject;


/**
 * A connected Terminal session.
 */
public class TerminalSession extends XTermWidget
                             implements ConsoleOutputEvent.Handler, 
                                        ProcessExitEvent.Handler,
                                        ResizeTerminalEvent.Handler,
                                        TerminalDataInputEvent.Handler,
                                        TerminalTitleEvent.Handler
{
   /**
    * 
    * @param secureInput securely send user input to server
    * @param sequence number used as part of default terminal title
    * @param handle terminal handle if reattaching, null if new terminal
    */
   public TerminalSession(final ShellSecureInput secureInput, 
                          int sequence, String handle)
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      secureInput_ = secureInput;
      sequence_ = sequence;
      terminalHandle_ = handle;
      setHeight("100%");
      setTitle("Terminal " + sequence_);
   }
   
   @Inject
   private void initialize(WorkbenchServerOperations server,
                           EventBus events)
   {
      server_ = server;
      eventBus_ = events; 
   } 
   
   /**
    * Create a terminal process and connect to it.
    */
   public void connect()
   {
      setNewTerminal(getHandle() == null);
      
      server_.startTerminal(80, 25, getHandle(), getTitle(), getSequence(),
                            new ServerRequestCallback<ConsoleProcess>()
      {
         @Override
         public void onResponseReceived(ConsoleProcess consoleProcess)
         {
            consoleProcess_ = consoleProcess;
            
            if (getInteractionMode() != ConsoleProcessInfo.INTERACTION_ALWAYS)
            {
               writeError("Unsupported ConsoleProcess interaction mode");
               return;
            } 

            if (consoleProcess_ != null)
            {
               addHandlerRegistration(consoleProcess_.addConsoleOutputHandler(TerminalSession.this));
               addHandlerRegistration(consoleProcess_.addProcessExitHandler(TerminalSession.this));
               addHandlerRegistration(addResizeTerminalHandler(TerminalSession.this));
               addHandlerRegistration(addTerminalDataInputHandler(TerminalSession.this));
               addHandlerRegistration(addTerminalTitleHandler(TerminalSession.this));

               consoleProcess.start(new ServerRequestCallback<Void>()
               {
                  @Override
                  public void onResponseReceived(Void response)
                  {
                     eventBus_.fireEvent(new TerminalSessionStartedEvent(TerminalSession.this));
                  }
                  
                  @Override
                  public void onError(ServerError error)
                  {
                     writeError(error.getUserMessage());
                  }
               });
            }
         }
      
         @Override
         public void onError(ServerError error)
         {
            writeError(error.getUserMessage());
         }
         
      });
   }
   
   @Override
   public void onConsoleOutput(ConsoleOutputEvent event)
   {
      write(event.getOutput());
   }
   
   @Override
   public void onProcessExit(ProcessExitEvent event)
   {
      unregisterHandlers();
      if (consoleProcess_ != null)
      {
         consoleProcess_.reap(new VoidServerRequestCallback());
      }
     
      eventBus_.fireEvent(new TerminalSessionStoppedEvent(this));
   }
   
   @Override
   public void onResizeTerminal(ResizeTerminalEvent event)
   {
      consoleProcess_.resizeTerminal(
            event.getCols(), event.getRows(),
            new VoidServerRequestCallback() 
            {
               @Override
               public void onError(ServerError error)
               {
                  writeln(error.getUserMessage());
               }
            });
   }
   
   @Override
   public void onTerminalDataInput(TerminalDataInputEvent event)
   {
      secureInput_.secureString(event.getData(), new CommandWithArg<String>() 
      {
         @Override
         public void execute(String arg) // success
         {
            consoleProcess_.writeStandardInput(
               ShellInput.create(arg,  true /* echo input*/), 
               new VoidServerRequestCallback() {
                  @Override
                  public void onError(ServerError error)
                  {
                     writeln(error.getUserMessage());
                  }
               });
          }
      },
      new CommandWithArg<String>()
      {
         @Override
         public void execute(String errorMessage) // failure
         {
            writeln(errorMessage); 
         }
      });
   }
   
   @Override
   public void onTerminalTitle(TerminalTitleEvent event)
   {
      caption_ = event.getTitle();
      eventBus_.fireEvent(new TerminalCaptionEvent(this));
   }
   
   public String getCaption()
   {
      return caption_;
   }
   
   public void setCaption(String caption)
   {
      caption_ = caption;
   }

   private int getInteractionMode()
   {
      if (consoleProcess_ != null)
         return consoleProcess_.getProcessInfo().getInteractionMode();
      else
         return ConsoleProcessInfo.INTERACTION_NEVER;
   } 

   protected void addHandlerRegistration(HandlerRegistration reg)
   {
      registrations_.add(reg);
   }
   
   protected void unregisterHandlers()
   {
      registrations_.removeHandler();
   }

   protected void writeError(String msg)
   {
      write(AnsiCode.ForeColor.RED + "Error: " + msg + AnsiCode.DEFAULTCOLORS);
   }

   @Override
   protected void onDetach()
   {
      super.onDetach();
      unregisterHandlers();
   }
  
   @Override
   public void setVisible(boolean isVisible)
   {
      super.setVisible(isVisible);
      if (isVisible)
      {
         // Inform the terminal that there may have been a resize. This could 
         // happen on first display, or if the terminal was hidden behind other
         // terminal sessions and there was a resize.
         // A delay is needed to give the xterm.js implementation an
         // opportunity to be ready for this.
         Scheduler.get().scheduleDeferred(new ScheduledCommand()
         {
            @Override
            public void execute()
            {
               onResize();
            }
         });
      }
   }
   
   /**
    * A unique handle for this terminal instance. Corresponds to the 
    * server-side ConsoleProcess handle.
    * @return Opaque string handle for this terminal instance, or null if
    * terminal has never been attached to a server ConsoleProcess.
    */
   public String getHandle()
   {
      if (consoleProcess_ == null)
      {
         return terminalHandle_;
      }
      
      return consoleProcess_.getProcessInfo().getHandle();
   }
   
   /**
    * The sequence number of the terminal, used in creation of the default
    * title, e.g. "Terminal 3".
    * @return The sequence number that was passed to the constructor.
    */
   public int getSequence()
   {
      return sequence_;
   }
   
   /**
    * Forcibly terminate the process associated with this terminal session.
    */
   public void terminate()
   {
      if (consoleProcess_ != null)
      {
         consoleProcess_.interrupt(new SimpleRequestCallback<Void>()
         {
            @Override
            public void onResponseReceived(Void response)
            {
               consoleProcess_.reap(new VoidServerRequestCallback());
            }
         });
      }
   }

   private final ShellSecureInput secureInput_;
   private HandlerRegistrations registrations_ = new HandlerRegistrations();
   private ConsoleProcess consoleProcess_;
   private String caption_ = new String();
   private final int sequence_;
   private final String terminalHandle_;
   
   // Injected ---- 
   private WorkbenchServerOperations server_; 
   private EventBus eventBus_;

}
