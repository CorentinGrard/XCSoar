// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include "net/http/Init.hpp"
#include "lib/curl/Global.hxx"
#include "co/InjectTask.hxx"
#include "co/InvokeTask.hxx"
#include "co/Task.hxx"
#include "util/BindMethod.hxx"

#include <future>
#include <optional>
#include <stdexcept>

/**
 * Runs a coroutine on the network thread and waits for it, like
 * ShowCoFunctionDialog() without the dialog.  Call it from a worker
 * thread, never the core main thread or the network thread.
 */
template<typename T>
class BlockingNetworkTask {
  std::optional<T> result;
  std::promise<void> done;

  static Co::InvokeTask
  Store(Co::Task<T> task, std::optional<T> &result)
  {
    result = co_await std::move(task);
  }

  void OnCompletion(std::exception_ptr error) noexcept {
    if (error)
      done.set_exception(error);
    else
      done.set_value();
  }

public:
  T Run(Co::Task<T> task) {
    if (Net::curl == nullptr)
      throw std::runtime_error("No network");

    auto future = done.get_future();
    Co::InjectTask inject{Net::curl->GetEventLoop()};
    inject.Start(Store(std::move(task), result),
                 BIND_THIS_METHOD(OnCompletion));
    future.get();
    return std::move(*result);
  }
};

template<typename T>
static inline T
RunNetworkTask(Co::Task<T> task)
{
  return BlockingNetworkTask<T>{}.Run(std::move(task));
}
