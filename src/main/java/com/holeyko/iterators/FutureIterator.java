package com.holeyko.iterators;

import java.util.Iterator;

public interface FutureIterator<T> extends Iterator<T> {
    T showNext();
}
