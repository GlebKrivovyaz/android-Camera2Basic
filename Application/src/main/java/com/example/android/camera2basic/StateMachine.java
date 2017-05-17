package com.example.android.camera2basic;

import android.support.annotation.NonNull;

/**
 * Created by grigory on 31.01.17.
 */

// keep Enum ref
// keep states names
// log transitions between states, events and etc

public class StateMachine
{
    private static final int MAX_STATES = 32;

    public static abstract class State
    {
        protected void onEnter(@NonNull StateMachine parent)
        {
        }

        protected void onEvent(@NonNull StateMachine parent, @NonNull Object event)
        {
        }

        protected void onLeave(@NonNull StateMachine parent)
        {
        }
    }

    private State currentState;
    private int currentIndex = -1;
    private final State states[] = new State[MAX_STATES];

    public void addState(@NonNull Enum index, @NonNull State state)
    {
        if (index.ordinal() < 0 || index.ordinal() >= MAX_STATES) throw new RuntimeException("Wrong index: index");
        if (states[index.ordinal()] != null) throw new RuntimeException("Assertation failed: states[index] != null");
        states[index.ordinal()] = state;
    }

    public void switchState(@NonNull Enum index)
    {
        if (index.ordinal() < 0 || index.ordinal() >= MAX_STATES) throw new RuntimeException("Wrong index: index");
        if (states[index.ordinal()] == null) throw new RuntimeException("Assertation failed: states[index] == null");
        if (currentState != null) {
            currentState.onLeave(this);
        }
        currentIndex = index.ordinal();
        currentState = states[currentIndex];
        currentState.onEnter(this);
    }

    public void sendEvent(@NonNull Object event)
    {
        if (currentState == null) throw new RuntimeException("Assertation failed: currentState == null");
        currentState.onEvent(this, event);
    }

    public int getCurrentStateId()
    {
        if (currentState == null) throw new RuntimeException("Assertation failed: currentState == null");
        return currentIndex;
    }

    public boolean isInState(@NonNull Enum index)
    {
        return getCurrentStateId() == index.ordinal();
    }

    public boolean isNotInState(@NonNull Enum index)
    {
        return getCurrentStateId() != index.ordinal();
    }
}
